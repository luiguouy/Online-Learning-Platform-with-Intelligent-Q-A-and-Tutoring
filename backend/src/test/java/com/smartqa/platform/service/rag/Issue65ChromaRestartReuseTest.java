package com.smartqa.platform.service.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【Issue #65 验收】真实 Chroma 下的重启复用与自愈测试（由 spike 分支
 * {@code spike/A-langchain4j-0362} 的 {@code Issue65GetOrCreateProbeTest} 探针转正而来）。
 *
 * <p>对应 #65 的两条验收勾选项 + 裁定评论里的恢复面：</p>
 * <ol>
 *   <li>collection 已存在时二次构造（模拟后端重启）<b>不得再报 409</b>
 *       → {@link #restartReusesExistingCollection()}</li>
 *   <li>重启后原入库向量仍可检索（证明真复用，而非清库重来）→ 同上</li>
 *   <li>存量环境交接（有 collection、无本地 uuid 缓存）可经
 *       {@code rag.chroma.collection-id} 迁移通道恢复 → {@link #migrationChannelWithInjectedId()}</li>
 *   <li>缓存过期（卷被清）自动重建自愈 → {@link #staleCacheSelfHeals()}</li>
 *   <li>#58 的按 docId 隔离删除在薄 store 的 where 映射下语义保持 → {@link #deleteByDocIdIsolates()}</li>
 * </ol>
 *
 * <p><b>环境依赖与 CI 兼容</b>：需要真实 Chroma（可用 {@code RAG_SPIKE_CHROMA_URL} 覆盖地址，
 * 默认 {@code http://localhost:8000}）。连不上即 assume 整类跳过——CI 的 backend job 没有
 * Chroma service，本类在 CI 上是 skip 而非红；本地按 #53 SOP 起
 * {@code chromadb/chroma:0.6.3} 即为 #65 的正式复测。</p>
 *
 * <p><b>为什么每次跑用独立 collection 名</b>：0.6.3 的 {@code DELETE /api/v1/collections/{name}}
 * 因服务端 coroutine bug 不可用（spike 实测，本测试的二轮探针再次确认），无法"清起点"；
 * 带时间戳的唯一名让残留无害（uuid 缓存文件在临时目录，不会把陈旧 uuid 喂给新名字）。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Issue65ChromaRestartReuseTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("RAG_SPIKE_CHROMA_URL", "http://localhost:8000");
    private static final float[] FIXED_VECTOR = {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
    private static final Embedding QUERY = Embedding.from(FIXED_VECTOR);

    /** 本类全部用例共用的唯一 collection 名与独立缓存目录（@TempDir 注入）。 */
    private static String collectionBase;
    private static Path cacheDir;

    /** 第一个实例（"重启前"），由 order=1 用例创建，order=2/3 复用其入库数据。 */
    private static ChromaThinEmbeddingStore beforeRestart;
    private static String createdCollectionId;

    @BeforeAll
    static void setUp(@TempDir Path tmp) {
        collectionBase = "issue65_reuse_" + System.currentTimeMillis();
        cacheDir = tmp;
    }

    private static boolean chromaReachable() {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/api/v1/heartbeat"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static void requireChroma() {
        Assumptions.assumeTrue(chromaReachable(),
                "Chroma 未在 " + BASE_URL + " 可达，跳过 #65 真实环境验收（CI 上不强制）");
    }

    private static ChromaThinEmbeddingStore storeFor(String name, String injectedId) {
        return ChromaThinEmbeddingStore.builder()
                .baseUrl(BASE_URL)
                .collectionName(name)
                .collectionId(injectedId)
                .idCacheDir(cacheDir)
                .build();
    }

    private static TextSegment segment(String text, String docId, int chunkIndex) {
        // 写入侧形态与 #82 修复后的 DocumentIngestionService 一致：数值字段以字符串入 metadata
        return TextSegment.from(text, new Metadata()
                .put("docId", docId)
                .put("chunkIndex", String.valueOf(chunkIndex)));
    }

    private static List<EmbeddingMatch<TextSegment>> hits(ChromaThinEmbeddingStore store) {
        return store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(QUERY)
                .maxResults(10)
                .minScore(0.0)
                .build()).matches();
    }

    @Test
    @Order(1)
    @DisplayName("#65 前置：collection 不存在时首次构造应创建成功并可写入（uuid 落缓存）")
    void firstConstructionCreatesAndWrites() {
        requireChroma();
        beforeRestart = storeFor(collectionBase, null);
        createdCollectionId = beforeRestart.collectionId();
        assertTrue(createdCollectionId != null && !createdCollectionId.isBlank(), "应拿到服务端生成的 UUID");

        beforeRestart.addAll(List.of(Embedding.from(FIXED_VECTOR), Embedding.from(FIXED_VECTOR)),
                List.of(segment("课件A-切片1", "1", 0), segment("课件A-切片2", "1", 1)));
        assertEquals(2, hits(beforeRestart).size(), "刚写入的两条切片应可检索");

        // uuid 必须已持久化到缓存文件——这是重启复用的前提
        Path cacheFile = cacheDir.resolve("collection-" + collectionBase + ".id");
        assertDoesNotThrow(() -> assertTrue(Files.isRegularFile(cacheFile)), "构造成功后应写出 uuid 缓存文件");
    }

    @Test
    @Order(2)
    @DisplayName("#65 验收一+二：二次构造（模拟重启）不再 409，且原向量仍可检索（真复用非清库）")
    void restartReusesExistingCollection() {
        requireChroma();
        // 第二次装配 = 应用重启后再建一次 embeddingStore bean。
        // 旧 ChromaEmbeddingStore 在此处抛 409 UniqueConstraintError → APPLICATION FAILED TO START（#65 本体）。
        ChromaThinEmbeddingStore afterRestart = assertDoesNotThrow(
                () -> storeFor(collectionBase, null),
                "命中 #65：collection 已存在时二次启动失败");

        assertEquals(createdCollectionId, afterRestart.collectionId(),
                "重启应经 PUT 探测复用同一 collection，而不是重建");
        List<EmbeddingMatch<TextSegment>> after = hits(afterRestart);
        assertEquals(2, after.size(),
                "重启后切片数必须仍为 2；若为 0 说明走的是「清库重建」，用数据丢失换启动成功，不满足验收二");
        // #82 联动：metadata 经真实 Chroma 往返后仍是字符串形态且可被读取侧解析
        for (EmbeddingMatch<TextSegment> m : after) {
            assertEquals("1", m.embedded().metadata().getString("docId"));
        }
    }

    @Test
    @Order(3)
    @DisplayName("#65 迁移通道：缓存丢失 + collection 已存在时，注入 collection-id 可复用存量数据")
    void migrationChannelWithInjectedId() throws Exception {
        requireChroma();
        // 模拟"换了机器/删了工作目录但 Chroma 卷还在"：缓存文件消失
        Files.deleteIfExists(cacheDir.resolve("collection-" + collectionBase + ".id"));

        ChromaThinEmbeddingStore migrated = assertDoesNotThrow(
                () -> storeFor(collectionBase, createdCollectionId),
                "显式注入 id 必须经 PUT 探测直接复用，存量环境交接不该被迫重灌");
        assertEquals(createdCollectionId, migrated.collectionId());
        assertEquals(2, hits(migrated).size(), "迁移通道下原数据完整可见");

        // 迁移成功的副作用检查：id 落回缓存，后续重启不再依赖配置项
        assertTrue(Files.isRegularFile(cacheDir.resolve("collection-" + collectionBase + ".id")),
                "注入 id 复用成功后应写回缓存");
    }

    @Test
    @Order(4)
    @DisplayName("#58 语义重验：removeAll(IsEqualTo docId) 在真实 Chroma 上精确隔离删除（薄 store where 映射）")
    void deleteByDocIdIsolates() {
        requireChroma();
        String name = collectionBase + "_del";
        ChromaThinEmbeddingStore store = storeFor(name, null);

        store.addAll(
                List.of(Embedding.from(FIXED_VECTOR), Embedding.from(FIXED_VECTOR), Embedding.from(FIXED_VECTOR)),
                List.of(segment("课件1-切片", "1", 0), segment("课件1-切片B", "1", 1), segment("课件2-切片", "2", 0)));
        assertEquals(3, hits(store).size(), "删除前共 3 条");

        // DocumentIngestionService.removeDocumentVectors 的真实载体：只删 docId=1，不得越界
        store.removeAll(new IsEqualTo("docId", "1"));

        List<EmbeddingMatch<TextSegment>> left = hits(store);
        assertEquals(1, left.size(), "只应删掉 docId=1 的两条；若为 0 说明 where 没生效发生越界删除（#58 红线）");
        assertEquals("2", left.get(0).embedded().metadata().getString("docId"),
                "残留的必须是 docId=2 的向量（IsEqualTo 精确匹配验证）");
    }

    @Test
    @Order(5)
    @DisplayName("缓存过期自愈：卷被清（uuid 探不到）时构造应重建 collection 并更新缓存，而非启动失败")
    void staleCacheSelfHeals() throws Exception {
        requireChroma();
        String name = collectionBase + "_heal";
        // 预置一个服务端不存在的 ghost uuid 缓存（等价于"Chroma 卷被清但缓存文件残留"）
        String ghost = "99999999-9999-9999-9999-999999999999";
        Path cacheFile = cacheDir.resolve("collection-" + name + ".id");
        Files.writeString(cacheFile, ghost);

        ChromaThinEmbeddingStore healed = assertDoesNotThrow(() -> storeFor(name, null),
                "PUT 探测失败必须走创建路径自愈，不能让陈旧缓存锁死启动");

        assertNotEquals(ghost, healed.collectionId(), "应换成服务端新建的 UUID");
        assertEquals(healed.collectionId(), ChromaThinEmbeddingStore.readCachedId(cacheFile),
                "新 UUID 必须覆盖陈旧缓存");
        assertEquals(0, hits(healed).size(), "新 collection 应是空的（数据随卷清空，与 SOP 一致）");
    }
}
