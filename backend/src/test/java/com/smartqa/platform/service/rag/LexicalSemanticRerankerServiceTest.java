package com.smartqa.platform.service.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地词汇-语义跨特征重排序单元测试 (Lexical-Semantic Cross-Scorer / Reranker)
 */
class LexicalSemanticRerankerServiceTest {

    private LexicalSemanticRerankerService rerankerService;

    @BeforeEach
    void setUp() {
        rerankerService = new LexicalSemanticRerankerService();
    }

    @Test
    @DisplayName("面包屑吻合度与关键词精确覆盖能够提升相关切块排名")
    void testRerankWithKeywordAndBreadcrumbBoost() {
        // 切块 A: 泛泛讨论系统资源调度，向量相似度被评为 0.85，但不是死锁章节
        TextSegment segA = TextSegment.from(
                "[章节导航: 第一章 操作系统概论 > 1.2 资源管理]\n系统需要协调分配各种物理与逻辑资源，避免并发访问冲突。",
                Metadata.from(Map.of("docId", "101", "chunkIndex", "0"))
        );

        // 切块 B: 明确阐述死锁的定义与必要条件，向量相似度被评为 0.78（略低），但面包屑和关键词极度精准
        TextSegment segB = TextSegment.from(
                "[章节导航: 第二章 进程管理 > 2.4 死锁与预防]\n死锁是指两个或多个进程因争夺资源而互相等待。死锁产生的四个必要条件包括互斥、占有且等待、不可抢占和循环等待。",
                Metadata.from(Map.of("docId", "101", "chunkIndex", "1"))
        );

        String keyA = LexicalSemanticRerankerService.makeSegmentKey(segA);
        String keyB = LexicalSemanticRerankerService.makeSegmentKey(segB);

        Map<String, Double> denseScores = new HashMap<>();
        denseScores.put(keyA, 0.85);
        denseScores.put(keyB, 0.78);

        Map<String, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(keyA, 0.5);
        bm25Scores.put(keyB, 8.2); // BM25 词法分数极高命中“死锁”

        String query = "什么是死锁？产生死锁的四个必要条件是什么？";
        var result = rerankerService.rerank(query, List.of(segA, segB), denseScores, bm25Scores, 2);

        assertEquals(2, result.rerankedSegments().size());
        // 经过 Cross-Scoring 重排，segB 应当逆转上升至第 1 位
        assertSame(segB, result.rerankedSegments().get(0), "包含精确大纲与关键词的切块 B 应被重排序至第 1 位");

        double scoreB = result.finalScoreMap().get(keyB);
        double scoreA = result.finalScoreMap().get(keyA);
        assertTrue(scoreB > scoreA, "切块 B 的综合得分应高于切块 A");
    }

    @Test
    @DisplayName("重排序得分严格落在 [0.70, 1.00] 契约区间内")
    void testRerankScoreThresholdContract() {
        TextSegment seg = TextSegment.from(
                "虚拟内存是操作系统重要的功能组件。",
                Metadata.from(Map.of("docId", "101", "chunkIndex", "0"))
        );
        String key = LexicalSemanticRerankerService.makeSegmentKey(seg);

        Map<String, Double> denseScores = Map.of(key, 0.72);
        Map<String, Double> bm25Scores = Map.of(key, 1.5);

        var result = rerankerService.rerank("虚拟内存", List.of(seg), denseScores, bm25Scores, 1);
        assertEquals(1, result.rerankedSegments().size());
        Double score = result.finalScoreMap().get(key);
        assertNotNull(score);
        assertTrue(score >= 0.70 && score <= 1.00, "得分必须满足出处下发契约阈值 [0.70, 1.00]，实际: " + score);
    }

    @Test
    @DisplayName("空候选池安全保底返回")
    void testEmptyCandidates() {
        var result = rerankerService.rerank("测试", List.of(), Map.of(), Map.of(), 5);
        assertTrue(result.rerankedSegments().isEmpty());
        assertTrue(result.finalScoreMap().isEmpty());

        var nullResult = rerankerService.rerank("测试", null, null, null, 5);
        assertTrue(nullResult.rerankedSegments().isEmpty());
    }
}
