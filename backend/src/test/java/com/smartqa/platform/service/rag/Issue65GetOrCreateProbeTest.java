package com.smartqa.platform.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #65 修复可行性探针（spike/A-langchain4j-0362，<b>验证用，不进主干</b>）。
 *
 * <p><b>❗ 本探针已经得出结论（详见 Issue #65 的 spike 结论帖）：</b>
 * langchain4j <b>0.35.0 与 0.36.2 在本工程环境（chromadb 0.6.3）上行为一致，均在构造期抛 409</b>。
 * 升级不解决 #65。原因：0.36.2 虽有 get-or-create 分支，但它靠
 * {@code GET /api/v1/collections/{collection_name}}（<b>按名字</b>）判断存在性，
 * 而 chromadb 0.6.3 对该端点返回 <b>HTTP 400</b>（它要的是 UUID）→ “已存在”被误判为“不存在”
 * → 仍走 createCollection → 409。同理，本类用来清起点
 * {@code DELETE /api/v1/collections/{collection_name}} 在 0.6.3 上也不可用，
 * <b>因此重跑本探针前必须手工清掉 {@code spike_issue65_probe} 这个 collection</b>，
 * 否则第一个用例就会因起点不净而假红（已实测到）。</p>
 *
 * <p>#65 的根因点被精确界定在「{@code ChromaEmbeddingStore} 构造期无条件
 * {@code createCollection}」这一处，因此本探针<b>不启 Spring 上下文、不连 MySQL、不加载 BGE</b>，
 * 直接两次构造 store 来复现"应用重启"，比起整个后端更可控、更可重复。</p>
 *
 * <p>对应 #65 正文的两条验收勾选项：</p>
 * <ol>
 *   <li>停掉后端再起，能够<b>正常启动</b>（collection 已存在时不报 409）→ {@link #secondConstructionShouldNotThrow409()}</li>
 *   <li>重启后<b>原先入库的向量仍可检索到</b>（证明真复用了 collection，而非清库重来）
 *       → {@link #dataWrittenBeforeRestartIsStillSearchableAfter()}</li>
 * </ol>
 *
 * <p>环境依赖：真实 Chroma（base-url 可用 {@code RAG_SPIKE_CHROMA_URL} 覆盖）。
 * <b>连不上即 assume 跳过而非失败</b>，因此在没有 Chroma 的 CI 上是 skip 而不是红。</p>
 *
 * <p>用独立 collection 名 {@code spike_issue65_probe}，<b>不碰</b>生产库
 * {@code smart_qa_course_docs}。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Issue65GetOrCreateProbeTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("RAG_SPIKE_CHROMA_URL", "http://localhost:8000");
    private static final String COLLECTION = "spike_issue65_probe";
    /** 固定向量：维度与取值都无所谓，只要两次构造后用同一个即可保证余弦相似度为 1。 */
    private static final float[] FIXED_VECTOR = {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};

    private static final Embedding QUERY = Embedding.from(FIXED_VECTOR);

    @BeforeAll
    static void requireRealChroma() {
        // 起点必须干净：collection 若残留，会干扰"第一次构造=创建"这一步的语义
        deleteCollectionQuietly();
    }

    private static ChromaEmbeddingStore newStore() {
        return ChromaEmbeddingStore.builder()
                .baseUrl(BASE_URL)
                .collectionName(COLLECTION)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    /** Chroma 是否真的可达；不可达则整个类 skip，绝不降级成"看起来通过"。 */
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

    private static void deleteCollectionQuietly() {
        try {
            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(BASE_URL + "/api/v1/collections/" + COLLECTION))
                            .timeout(Duration.ofSeconds(5)).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {
            // 清理失败不阻断：下面有显式断言会暴露真实状态
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("#65 前置：Chroma 可达 + 首次构造（collection 不存在 → 应创建）不抛")
    void firstConstructionCreatesCollection() {
        Assumptions.assumeTrue(chromaReachable(),
                "Chroma 未在 " + BASE_URL + " 可达，跳过 #65 探针（不在 CI 强制要求范围内）");

        ChromaEmbeddingStore store = assertDoesNotThrow(Issue65GetOrCreateProbeTest::newStore,
                "collection 不存在时首次构造就抛，说明连创建路径都不通");

        String id = store.add(Embedding.from(FIXED_VECTOR), TextSegment.from("探针切片"));
        assertTrue(id != null && !id.isBlank(), "应返回写入 id");

        // 立即自检：同一个实例能检索到自己刚写的
        assertEquals(1, hits(store).size(), "刚写入的切片应当可被检索到");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("#65 验收一：collection 已存在时再次构造不得报 409（模拟后端重启）")
    void secondConstructionShouldNotThrow409() {
        Assumptions.assumeTrue(chromaReachable(), "Chroma 不可达，跳过");

        // 第一次构造（创建）
        newStore();

        // 第二次构造 = 应用重启后再装配一次 embeddingStore bean
        // 0.35.0 在此处抛 status code: 409 / UniqueConstraintError → APPLICATION FAILED TO START
        try {
            newStore();
        } catch (Throwable t) {
            failWithAttribution(t.getMessage() + " / " + t.getClass().getName());
        }
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("#65 验收二：重启后原先入库的向量仍可检索（证明确实复用，而非清库重来）")
    void dataWrittenBeforeRestartIsStillSearchableAfter() {
        Assumptions.assumeTrue(chromaReachable(), "Chroma 不可达，跳过");

        deleteCollectionQuietly();
        // 「重启前」：用第一个实例写入两条切片
        ChromaEmbeddingStore beforeRestart = newStore();
        beforeRestart.add(Embedding.from(FIXED_VECTOR), TextSegment.from("课件A-切片1"));
        beforeRestart.add(Embedding.from(FIXED_VECTOR), TextSegment.from("课件A-切片2"));
        assertEquals(2, hits(beforeRestart).size(), "重启前应有 2 条");

        // 「重启后」：新建实例（0.35.0 在这一步就起不来），应仍能看到那 2 条
        ChromaEmbeddingStore afterRestart = assertDoesNotThrow(Issue65GetOrCreateProbeTest::newStore,
                "模拟重启时构造失败");
        var after = hits(afterRestart);

        assertEquals(2, after.size(),
                "重启后切片数必须仍为 2；若为 0 说明走的是「清库重建」而不是复用，"
                        + "那等于用数据丢失换启动成功，不满足 #65 验收二");
        assertFalse(after.isEmpty(), "重启后不得检索为空");
    }

    private java.util.List<EmbeddingMatch<TextSegment>> hits(ChromaEmbeddingStore store) {
        EmbeddingSearchResult<TextSegment> r = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(QUERY)
                .maxResults(10)
                .build());
        return r.matches();
    }

    private static void failWithAttribution(String msg) {
        boolean is409 = msg.contains("409") || msg.contains("UniqueConstraint") || msg.contains("already exists");
        throw new AssertionError((is409
                ? "命中 #65 描述的 409 故障：collection 已存在时构造即失败（升级未解决该问题）。"
                : "二次构造抛出非 409 异常，需单独定位。") + "\n实际：" + msg);
    }
}
