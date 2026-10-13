package com.smartqa.platform.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【Issue #65】{@link ChromaThinEmbeddingStore} 纯函数契约单测（不依赖 Chroma / Spring，CI 可直接跑）。
 *
 * <p>手法沿用 #82 的分层策略：<b>解析/构造规则用纯单测在 CI 里长期钉住，真实往返行为由
 * {@link Issue65ChromaRestartReuseTest} 在真实 Chroma 上验收</b>。本类钉住的是最容易在重构中
 * 悄悄跑偏的四组语义：score 公式、请求体形状、where 映射、uuid 缓存文件 IO。</p>
 */
class ChromaThinEmbeddingStoreContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── score 公式：与 langchain4j-chroma 0.35 distanceToScore 逐字节一致（1 - d/2） ──

    @Test
    @DisplayName("score 公式必须等于 langchain4j 0.35 的 1 - distance/2（#82 验收：得分口径不变）")
    void scoreFormulaMatchesLangchain4j() {
        assertEquals(1.0, ChromaThinEmbeddingStore.distanceToScore(0.0), "零距离 → 满分 1");
        assertEquals(0.0, ChromaThinEmbeddingStore.distanceToScore(2.0), "距离2 → 0 分（阈值分界点）");
        assertEquals(-1.0, ChromaThinEmbeddingStore.distanceToScore(4.0));
        assertEquals(0.85, ChromaThinEmbeddingStore.distanceToScore(0.3), 1e-9);
    }

    // ── add 请求体：四字段对齐；无 segment 时省略 metadatas/documents（对齐 langchain4j 行为） ──

    @Test
    @DisplayName("buildAddBody：ids/embeddings/metadatas/documents 逐位对齐")
    void addBodyAlignsAllFourLists() {
        Embedding e1 = Embedding.from(new float[]{1f, 2f});
        Embedding e2 = Embedding.from(new float[]{3f, 4f});
        TextSegment s1 = TextSegment.from("片段一", new Metadata().put("docId", "18").put("chunkIndex", "0.0"));
        TextSegment s2 = TextSegment.from("片段二", new Metadata().put("docId", "19").put("chunkIndex", "1.0"));

        Map<String, Object> body = ChromaThinEmbeddingStore.buildAddBody(
                List.of("id-a", "id-b"), List.of(e1, e2), List.of(s1, s2));

        assertEquals(List.of("id-a", "id-b"), body.get("ids"));
        @SuppressWarnings("unchecked")
        List<float[]> vectors = (List<float[]>) body.get("embeddings");
        assertEquals(2, vectors.size());
        assertEquals(1f, vectors.get(0)[0]);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metadatas = (List<Map<String, Object>>) body.get("metadatas");
        assertEquals("18", metadatas.get(0).get("docId"));
        // #82 修复后写入侧统一字符串，原样透传（含历史 "3.0" 形态），薄 store 不得再改形态
        assertEquals("0.0", metadatas.get(0).get("chunkIndex"));
        @SuppressWarnings("unchecked")
        List<String> documents = (List<String>) body.get("documents");
        assertEquals(List.of("片段一", "片段二"), documents);
    }

    @Test
    @DisplayName("buildAddBody：segments 为 null 时不带 metadatas/documents 字段")
    void addBodyOmitsOptionalFieldsWithoutSegments() {
        Map<String, Object> body = ChromaThinEmbeddingStore.buildAddBody(
                List.of("x"), List.of(Embedding.from(new float[]{1f})), null);
        assertTrue(body.containsKey("ids") && body.containsKey("embeddings"));
        assertFalse(body.containsKey("metadatas"), "缺省字段必须整体省略而非发 null，避免服务端校验歧义");
        assertFalse(body.containsKey("documents"));
    }

    // ── query 请求体 ──

    @Test
    @DisplayName("buildQueryBody：单查询向量二维数组 + n_results + include；无 filter 时不发 where")
    void queryBodyShape() {
        Map<String, Object> body = ChromaThinEmbeddingStore.buildQueryBody(
                Embedding.from(new float[]{0.5f, 1.5f}), 4, null);
        assertEquals(List.of(List.of(0.5f, 1.5f)), body.get("query_embeddings"));
        assertEquals(4, body.get("n_results"));
        assertFalse(body.containsKey("where"));
        assertTrue(((List<?>) body.get("include")).contains("distances"));
    }

    @Test
    @DisplayName("buildQueryBody：带 where 过滤时字段必须存在（search 内嵌 filter 路径）")
    void queryBodyWithWhere() {
        Map<String, Object> body = ChromaThinEmbeddingStore.buildQueryBody(
                Embedding.from(new float[]{1f}), 2, Map.of("docId", "18"));
        assertEquals(Map.of("docId", "18"), body.get("where"));
    }

    // ── Filter → Chroma where 映射：仅 IsEqualTo（业务唯一用法，#58），其余必须显式拒绝 ──

    @Test
    @DisplayName("whereFor：IsEqualTo → {key: value} 简写（与 ChromaMetadataFilterMapper.mapEqual 同形）")
    void whereForIsEqualTo() {
        assertEquals(Map.of("docId", "18"),
                ChromaThinEmbeddingStore.whereFor(new IsEqualTo("docId", "18")));
        // 数值型 value 也必须规整为字符串（写入侧 #82 已统一字符串，查询侧保持同形态）
        assertEquals(Map.of("docId", "18"),
                ChromaThinEmbeddingStore.whereFor(new IsEqualTo("docId", 18)));
    }

    @Test
    @DisplayName("whereFor：非 IsEqualTo 必须抛 UnsupportedOperationException，绝不静默给错语义")
    void whereForRejectsUnsupportedFilters() {
        assertThrows(UnsupportedOperationException.class,
                () -> ChromaThinEmbeddingStore.whereFor(new IsGreaterThan("docId", 1)));
    }

    // ── query 响应解析：嵌套二维数组 → EmbeddingMatch ──

    @Test
    @DisplayName("parseQueryMatches：ids/distances/metadatas/documents 对齐还原，score=1-d/2")
    void parsesQueryResponseIntoMatches() throws Exception {
        String json = """
                {"ids":[["a","b"]],
                 "distances":[[0.2,1.4]],
                 "metadatas":[[{"docId":"18","chunkIndex":"3.0"},{"docId":"19","chunkIndex":"0.0"}]],
                 "documents":[["命中一","命中二"]]}""";
        List<EmbeddingMatch<TextSegment>> matches =
                ChromaThinEmbeddingStore.parseQueryMatches(MAPPER.readTree(json));

        assertEquals(2, matches.size());
        EmbeddingMatch<TextSegment> m0 = matches.get(0);
        assertEquals("a", m0.embeddingId());
        assertEquals(0.9, m0.score(), 1e-9);
        assertEquals("命中一", m0.embedded().text());
        // #82 形态：chunkIndex 经 Chroma 往返是字符串 "3.0"，薄 store 原样交给读取侧兼容解析
        assertEquals("3.0", m0.embedded().metadata().getString("chunkIndex"));
        assertEquals(0.3, matches.get(1).score(), 1e-9);
    }

    @Test
    @DisplayName("parseQueryMatches：空结果与缺失字段防御（服务端可能省略 documents 行）")
    void parsesEmptyOrPartialResponse() throws Exception {
        assertTrue(ChromaThinEmbeddingStore.parseQueryMatches(MAPPER.readTree("{}")).isEmpty());
        assertTrue(ChromaThinEmbeddingStore.parseQueryMatches(MAPPER.readTree("{\"ids\":[[]]}")).isEmpty());

        List<EmbeddingMatch<TextSegment>> matches = ChromaThinEmbeddingStore.parseQueryMatches(
                MAPPER.readTree("{\"ids\":[[\"a\"]],\"distances\":[[0.5]],\"metadatas\":[[null]],\"documents\":[[null]]}"));
        assertEquals(1, matches.size());
        assertEquals("a", matches.get(0).embeddingId());
        assertNull(matches.get(0).embedded(), "document 为 null 时不得伪造空 TextSegment");
    }

    // ── uuid 缓存文件 IO（#65 重启复用的关键载体） ──

    @Test
    @DisplayName("缓存往返：写入后读回同一 uuid；目录不存在时自动创建")
    void cacheRoundTrip(@TempDir Path tmp) {
        Path file = tmp.resolve("nested/collection-foo.id");
        ChromaThinEmbeddingStore.writeCachedId(file, "e8b21227-06bd-4b0a-a799-de99f3f1569e");
        assertEquals("e8b21227-06bd-4b0a-a799-de99f3f1569e", ChromaThinEmbeddingStore.readCachedId(file));
    }

    @Test
    @DisplayName("缓存缺失/空文件/纯空白一律按 null 处理（触发创建路径而非误判存在）")
    void cacheTreatsMissingOrBlankAsNull(@TempDir Path tmp) throws Exception {
        assertNull(ChromaThinEmbeddingStore.readCachedId(tmp.resolve("absent.id")));
        Path blank = tmp.resolve("collection-blank.id");
        Files.writeString(blank, "   \n");
        assertNull(ChromaThinEmbeddingStore.readCachedId(blank));
    }
}
