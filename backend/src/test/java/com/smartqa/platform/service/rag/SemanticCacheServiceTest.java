package com.smartqa.platform.service.rag;

import com.smartqa.platform.vo.SseReferenceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高频问答语义缓存单元测试 (Semantic Cache)
 */
class SemanticCacheServiceTest {

    private SemanticCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new SemanticCacheService();
    }

    @Test
    @DisplayName("余弦相似度计算准确性")
    void cosineSimilarityCalculation() {
        // 完全相同向量 -> 1.0
        float[] v1 = new float[]{1.0f, 2.0f, 3.0f};
        float[] v2 = new float[]{1.0f, 2.0f, 3.0f};
        assertEquals(1.0, SemanticCacheService.cosineSimilarity(v1, v2), 0.0001);

        // 正交向量 -> 0.0
        float[] vOrth1 = new float[]{1.0f, 0.0f};
        float[] vOrth2 = new float[]{0.0f, 1.0f};
        assertEquals(0.0, SemanticCacheService.cosineSimilarity(vOrth1, vOrth2), 0.0001);

        // 反向向量 -> -1.0
        float[] vOpp1 = new float[]{1.0f, 1.0f};
        float[] vOpp2 = new float[]{-1.0f, -1.0f};
        assertEquals(-1.0, SemanticCacheService.cosineSimilarity(vOpp1, vOpp2), 0.0001);
    }

    @Test
    @DisplayName("语义等价高相似度提问命中缓存并返回完整数据")
    void highSimilarityHitReturnsCachedAnswer() {
        long courseId = 101L;
        float[] baseVector = new float[]{0.5f, 0.5f, 0.5f, 0.5f};
        // 极微小扰动向量（余弦相似度 > 0.99）
        float[] queryVector = new float[]{0.501f, 0.499f, 0.5f, 0.5f};

        SseReferenceVO ref = SseReferenceVO.builder()
                .docId(1L)
                .fileName("操作系统的概念.md")
                .chunkIndex(0)
                .score(0.95)
                .snippet("操作系统是核心系统软件...")
                .build();

        cacheService.put(courseId, "什么是操作系统？", baseVector, "操作系统是系统软件，管理软硬件资源。", List.of(ref));

        // 阈值 0.96 查找
        Optional<SemanticCacheService.CacheEntry> hit = cacheService.lookup(courseId, queryVector, 0.96);
        assertTrue(hit.isPresent(), "相似度极高应命中语义缓存");
        assertEquals("什么是操作系统？", hit.get().getQuestion());
        assertEquals("操作系统是系统软件，管理软硬件资源。", hit.get().getAnswer());
        assertEquals(1, hit.get().getReferences().size());
        assertEquals("操作系统的概念.md", hit.get().getReferences().get(0).getFileName());
    }

    @Test
    @DisplayName("低于阈值的提问未命中语义缓存")
    void lowSimilarityMissesCache() {
        long courseId = 101L;
        float[] v1 = new float[]{1.0f, 0.0f, 0.0f};
        float[] v2 = new float[]{0.8f, 0.6f, 0.0f}; // 相似度约为 0.80

        cacheService.put(courseId, "什么是进程？", v1, "进程是程序的一次执行过程。", List.of());

        Optional<SemanticCacheService.CacheEntry> hit = cacheService.lookup(courseId, v2, 0.96);
        assertFalse(hit.isPresent(), "相似度0.80低于0.96阈值，不得命中缓存");
    }

    @Test
    @DisplayName("严格保持课程租户隔离（SEC-1 铁律）")
    void courseTenantIsolationEnforced() {
        long courseA = 201L;
        long courseB = 202L;
        float[] vector = new float[]{0.5f, 0.5f, 0.5f};

        cacheService.put(courseA, "课程A考点", vector, "课程A解答", List.of());

        // 课程B使用同一向量查询，必须返回空
        Optional<SemanticCacheService.CacheEntry> hitB = cacheService.lookup(courseB, vector, 0.96);
        assertFalse(hitB.isPresent(), "跨课程不得读取缓存");

        // 课程A查询正常命中
        Optional<SemanticCacheService.CacheEntry> hitA = cacheService.lookup(courseA, vector, 0.96);
        assertTrue(hitA.isPresent());
    }

    @Test
    @DisplayName("课件变更时清空指定课程缓存保障强一致性")
    void invalidateCourseClearsCache() {
        long courseId = 301L;
        float[] vector = new float[]{1.0f, 0.0f};

        cacheService.put(courseId, "虚拟内存置换算法", vector, "FIFO与LRU算法", List.of());
        assertTrue(cacheService.lookup(courseId, vector, 0.96).isPresent());

        // 课件变更触发清空
        cacheService.invalidateCourse(courseId);

        assertFalse(cacheService.lookup(courseId, vector, 0.96).isPresent(), "失效后缓存应为空");
    }
}
