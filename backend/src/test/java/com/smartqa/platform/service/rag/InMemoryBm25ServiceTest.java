package com.smartqa.platform.service.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯内存 BM25 稀疏检索与 RRF 融合算法单元测试
 */
class InMemoryBm25ServiceTest {

    private InMemoryBm25Service bm25Service;

    @BeforeEach
    void setUp() {
        bm25Service = new InMemoryBm25Service();
    }

    @Test
    @DisplayName("专有名词与系统常量精准命中高分召回")
    void technicalTermsAndConstantsMatchAccurately() {
        long courseId = 101L;
        long docId = 2001L;

        TextSegment seg1 = TextSegment.from("在 Linux Socket 编程中，设置 SO_REUSEADDR 允许重用本地地址和端口。");
        Metadata m1 = seg1.metadata();
        m1.put("docId", String.valueOf(docId));
        m1.put("chunkIndex", "0");

        TextSegment seg2 = TextSegment.from("系统日志由 klogd 和 syslogd 守护进程负责收集与转储。");
        Metadata m2 = seg2.metadata();
        m2.put("docId", String.valueOf(docId));
        m2.put("chunkIndex", "1");

        TextSegment seg3 = TextSegment.from("用户密码在入库前采用 BCrypt 算法加盐哈希，提高安全性。");
        Metadata m3 = seg3.metadata();
        m3.put("docId", String.valueOf(docId));
        m3.put("chunkIndex", "2");

        bm25Service.addSegments(courseId, docId, List.of(seg1, seg2, seg3));

        // 检索专有名词 SO_REUSEADDR
        List<InMemoryBm25Service.Bm25Match> match1 = bm25Service.search(courseId, "请问 SO_REUSEADDR 是什么作用？", 3);
        assertFalse(match1.isEmpty(), "应命中 SO_REUSEADDR 对应切块");
        assertTrue(match1.get(0).getSegment().text().contains("SO_REUSEADDR"));
        assertTrue(match1.get(0).getScore() > 0.0);

        // 检索专有名词 klogd
        List<InMemoryBm25Service.Bm25Match> match2 = bm25Service.search(courseId, "klogd 进程的作用", 3);
        assertFalse(match2.isEmpty());
        assertTrue(match2.get(0).getSegment().text().contains("klogd"));

        // 检索 BCrypt
        List<InMemoryBm25Service.Bm25Match> match3 = bm25Service.search(courseId, "密码哈希 BCrypt", 3);
        assertFalse(match3.isEmpty());
        assertTrue(match3.get(0).getSegment().text().contains("BCrypt"));
    }

    @Test
    @DisplayName("严格保持课程租户级隔离（SEC-1）")
    void courseTenantIsolationEnforced() {
        long courseA = 1001L;
        long courseB = 1002L;

        TextSegment segA = TextSegment.from("课程A独有知识点：操作系统虚拟内存置换算法LRU与OPT。");
        bm25Service.addSegments(courseA, 10L, List.of(segA));

        // 课程B检索该关键词，必须返回空
        List<InMemoryBm25Service.Bm25Match> searchInB = bm25Service.search(courseB, "虚拟内存", 5);
        assertTrue(searchInB.isEmpty(), "跨课程检索不得泄漏其他课程的知识切块");

        // 课程A检索，正常命中
        List<InMemoryBm25Service.Bm25Match> searchInA = bm25Service.search(courseA, "虚拟内存", 5);
        assertEquals(1, searchInA.size());
    }

    @Test
    @DisplayName("级联删除正确清理对应文档切块")
    void removeByDocIdClearsIndexCorrectly() {
        long courseId = 201L;
        long docId = 3001L;

        TextSegment seg = TextSegment.from("死锁的四个必要条件：互斥、占有并等待、非抢占、循环等待。");
        bm25Service.addSegments(courseId, docId, List.of(seg));

        List<InMemoryBm25Service.Bm25Match> before = bm25Service.search(courseId, "死锁", 5);
        assertEquals(1, before.size());

        // 级联清理
        bm25Service.removeByDocId(docId);

        List<InMemoryBm25Service.Bm25Match> after = bm25Service.search(courseId, "死锁", 5);
        assertTrue(after.isEmpty(), "文档删除后 BM25 倒排索引必须被清空");
    }

    @Test
    @DisplayName("RRF 倒数排名融合算法综合加权与去重验证")
    void rrfFusionRankAndDeduplication() {
        // 构造切块A与切块B
        TextSegment segA = TextSegment.from("切块A内容");
        segA.metadata().put("docId", "1");
        segA.metadata().put("chunkIndex", "0");

        TextSegment segB = TextSegment.from("切块B内容");
        segB.metadata().put("docId", "1");
        segB.metadata().put("chunkIndex", "1");

        // 模拟 Dense 召回：A排第1(rank 1), B排第2(rank 2)
        EmbeddingMatch<TextSegment> denseA = new EmbeddingMatch<>(0.92, "id-a", null, segA);
        EmbeddingMatch<TextSegment> denseB = new EmbeddingMatch<>(0.85, "id-b", null, segB);
        List<EmbeddingMatch<TextSegment>> denseMatches = List.of(denseA, denseB);

        // 模拟 BM25 召回：B排第1(rank 1), A排第2(rank 2)
        InMemoryBm25Service.Bm25Match bm25B = new InMemoryBm25Service.Bm25Match(segB, 5.0);
        InMemoryBm25Service.Bm25Match bm25A = new InMemoryBm25Service.Bm25Match(segA, 4.0);
        List<InMemoryBm25Service.Bm25Match> bm25Matches = List.of(bm25B, bm25A);

        // 两者 RRF 得分都是 1/(60+1) + 1/(60+2)，融合后应去重并包含两个片段
        List<TextSegment> fused = bm25Service.fuseRrf(denseMatches, bm25Matches, 2);
        assertEquals(2, fused.size());

        // 测试仅在 Dense 命中时的平滑融合
        List<TextSegment> fusedOnlyDense = bm25Service.fuseRrf(denseMatches, List.of(), 1);
        assertEquals(1, fusedOnlyDense.size());
        assertEquals("切块A内容", fusedOnlyDense.get(0).text());

        // 测试仅在 BM25 命中时的平滑融合
        List<TextSegment> fusedOnlyBm25 = bm25Service.fuseRrf(List.of(), bm25Matches, 1);
        assertEquals(1, fusedOnlyBm25.size());
        assertEquals("切块B内容", fusedOnlyBm25.get(0).text());
    }
}
