package com.smartqa.platform.service.rag;

import com.smartqa.platform.vo.SseReferenceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高频问答语义缓存服务 (Semantic Cache)
 *
 * 核心目标：
 * 1. 拦截高频重复提问（余弦相似度 >= 0.96），直接以极低延迟（50ms）推流返回高质量历史解答，跳过大模型调用，节省 Token 预算；
 * 2. 课程多租户强隔离（SEC-1 铁律）：缓存按 courseId 分桶，严格杜绝跨课程缓存穿透；
 * 3. 课件更新强一致性：当教师上传新课件、删除课件或触发 reindex 时，自动清空该课程语义缓存，防止返回过时内容；
 * 4. 纯本地向量模型驱动：复用系统内置本地 BGE-Small-ZH 量化模型，零外部依赖，100% 离线可用。
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    /** 单门课程最大语义缓存容量，防无节制膨胀 */
    private static final int MAX_ENTRIES_PER_COURSE = 100;

    /** 默认语义命中阈值（0.96 保证高置信语义等价） */
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.96;

    /**
     * 语义缓存单项记录
     */
    public static class CacheEntry {
        private final String question;
        private final float[] vector;
        private final String answer;
        private final List<SseReferenceVO> references;
        private final long createdAt;

        public CacheEntry(String question, float[] vector, String answer, List<SseReferenceVO> references) {
            this.question = question;
            this.vector = vector;
            this.answer = answer;
            this.references = references != null ? Collections.unmodifiableList(references) : Collections.emptyList();
            this.createdAt = System.currentTimeMillis();
        }

        public String getQuestion() {
            return question;
        }

        public float[] getVector() {
            return vector;
        }

        public String getAnswer() {
            return answer;
        }

        public List<SseReferenceVO> getReferences() {
            return references;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }

    /** 课程级缓存分桶：courseId -> List<CacheEntry> */
    private final Map<Long, List<CacheEntry>> cacheStore = new ConcurrentHashMap<>();

    /**
     * 检索指定课程下是否存在语义等价的历史回答
     *
     * @param courseId    课程 ID（租户级过滤）
     * @param queryVector 提问嵌入向量
     * @param threshold   相似度阈值（>= 该阈值视为命中）
     * @return 命中的最佳 CacheEntry，未命中则返回 Optional.empty()
     */
    public Optional<CacheEntry> lookup(Long courseId, float[] queryVector, double threshold) {
        if (courseId == null || queryVector == null || queryVector.length == 0) {
            return Optional.empty();
        }

        List<CacheEntry> entries = cacheStore.get(courseId);
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }

        CacheEntry bestMatch = null;
        double maxSimilarity = -1.0;

        synchronized (entries) {
            for (CacheEntry entry : entries) {
                double sim = cosineSimilarity(queryVector, entry.vector);
                if (sim >= threshold && sim > maxSimilarity) {
                    maxSimilarity = sim;
                    bestMatch = entry;
                }
            }
        }

        if (bestMatch != null) {
            log.info("[SemanticCache] 命中语义缓存: courseId={}, 匹配问题='{}', 相似度={}",
                    courseId, bestMatch.getQuestion(), String.format("%.4f", maxSimilarity));
            return Optional.of(bestMatch);
        }

        return Optional.empty();
    }

    /**
     * 写入语义缓存
     *
     * @param courseId    课程 ID
     * @param question    提问文本
     * @param vector      提问嵌入向量
     * @param answer      AI 完整回答
     * @param references  课件出处列表
     */
    public void put(Long courseId, String question, float[] vector, String answer, List<SseReferenceVO> references) {
        if (courseId == null || !StringUtils.hasText(question) || vector == null || !StringUtils.hasText(answer)) {
            return;
        }

        List<CacheEntry> entries = cacheStore.computeIfAbsent(courseId, k -> Collections.synchronizedList(new LinkedList<>()));
        synchronized (entries) {
            // 容量控制：超限淘汰最老的条目
            if (entries.size() >= MAX_ENTRIES_PER_COURSE) {
                entries.remove(0);
            }
            entries.add(new CacheEntry(question, vector, answer, references));
        }

        log.debug("[SemanticCache] 写入语义缓存: courseId={}, 问题='{}', 当前课程缓存项={}",
                courseId, question, entries.size());
    }

    /**
     * 课件变更时清空指定课程的语义缓存，保障强一致性
     *
     * @param courseId 课程 ID
     */
    public void invalidateCourse(Long courseId) {
        if (courseId == null) {
            return;
        }
        List<CacheEntry> removed = cacheStore.remove(courseId);
        if (removed != null && !removed.isEmpty()) {
            log.info("[SemanticCache] 课件变更，已失效清空该课程语义缓存: courseId={}, 清除条数={}",
                    courseId, removed.size());
        }
    }

    /**
     * 清空全部缓存（仅供运维或测试使用）
     */
    public void clear() {
        cacheStore.clear();
        log.info("[SemanticCache] 已清空全部语义缓存");
    }

    /**
     * 计算两个浮点向量之间的余弦相似度
     */
    public static double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) {
            return 0.0;
        }

        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }

        if (norm1 <= 0.0 || norm2 <= 0.0) {
            return 0.0;
        }

        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
