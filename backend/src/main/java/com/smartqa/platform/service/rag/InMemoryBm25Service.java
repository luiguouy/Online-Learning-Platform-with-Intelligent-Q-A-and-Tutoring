package com.smartqa.platform.service.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯内存 BM25 稀疏检索服务 (In-Memory BM25 Service)
 *
 * 核心目标：
 * 1. 弥补向量检索（Bi-Encoder）对专业术语、API 缩写（如 SO_REUSEADDR、BCrypt、klogd）相似度计算容易偏移的短板；
 * 2. 维护按 courseId 隔离的轻量内存倒排索引，支持中英文双模分词与精准关键字打分；
 * 3. 提供 RRF (Reciprocal Rank Fusion) 倒数排名融合算法，将稠密向量与稀疏 BM25 召回结果进行归一化综合排序；
 * 4. 零外部重型依赖，100% 内存自包含；严格遵循 SEC-1 铁律，不引入任何权限过滤。
 */
@Service
public class InMemoryBm25Service {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBm25Service.class);

    /** BM25 核心调节参数 */
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /** RRF 融合平滑常数（行业公认最优常数 60） */
    public static final int RRF_K = 60;

    /** 匹配英文/数字/下划线连续单词（用于捕获 API、标识符与代码术语） */
    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z0-9_]+");

    /** 全局文档自增 ID 生成器 */
    private final AtomicLong docIdSequence = new AtomicLong(1);

    /** 课程级倒排索引桶：courseId -> CourseIndex */
    private final Map<Long, CourseIndex> courseIndices = new ConcurrentHashMap<>();

    /**
     * 单个文本片段索引项
     */
    public static class ChunkEntry {
        final long id;
        final long courseId;
        final long docId;
        final TextSegment segment;
        final int docLength;
        final Map<String, Integer> termFreqs;

        ChunkEntry(long id, long courseId, long docId, TextSegment segment, int docLength, Map<String, Integer> termFreqs) {
            this.id = id;
            this.courseId = courseId;
            this.docId = docId;
            this.segment = segment;
            this.docLength = docLength;
            this.termFreqs = termFreqs;
        }

        public TextSegment getSegment() {
            return segment;
        }
    }

    /**
     * BM25 检索匹配结果包装
     */
    public static class Bm25Match {
        private final TextSegment segment;
        private final double score;

        public Bm25Match(TextSegment segment, double score) {
            this.segment = segment;
            this.score = score;
        }

        public TextSegment getSegment() {
            return segment;
        }

        public double getScore() {
            return score;
        }
    }

    /**
     * 单门课程的独立倒排索引桶（保证多租户隔离与线程安全）
     */
    private static class CourseIndex {
        final Map<Long, ChunkEntry> chunks = new ConcurrentHashMap<>();
        final Map<String, Set<Long>> inverted = new ConcurrentHashMap<>();

        synchronized void add(ChunkEntry entry) {
            chunks.put(entry.id, entry);
            for (String term : entry.termFreqs.keySet()) {
                inverted.computeIfAbsent(term, k -> ConcurrentHashMap.newKeySet()).add(entry.id);
            }
        }

        synchronized void removeByDocId(Long docId) {
            List<Long> toRemoveIds = new ArrayList<>();
            for (ChunkEntry entry : chunks.values()) {
                if (entry.docId == docId) {
                    toRemoveIds.add(entry.id);
                }
            }
            for (Long id : toRemoveIds) {
                ChunkEntry removed = chunks.remove(id);
                if (removed != null) {
                    for (String term : removed.termFreqs.keySet()) {
                        Set<Long> set = inverted.get(term);
                        if (set != null) {
                            set.remove(id);
                            if (set.isEmpty()) {
                                inverted.remove(term);
                            }
                        }
                    }
                }
            }
        }

        double getAvgDocLength() {
            if (chunks.isEmpty()) {
                return 0.0;
            }
            long total = 0;
            for (ChunkEntry c : chunks.values()) {
                total += c.docLength;
            }
            return (double) total / chunks.size();
        }
    }

    /**
     * 向指定课程添加课件切块倒排索引
     *
     * @param courseId 课程 ID
     * @param docId    课件记录 ID
     * @param segments 切块列表
     */
    public void addSegments(Long courseId, Long docId, List<TextSegment> segments) {
        if (courseId == null || docId == null || segments == null || segments.isEmpty()) {
            return;
        }

        CourseIndex index = courseIndices.computeIfAbsent(courseId, k -> new CourseIndex());
        for (TextSegment segment : segments) {
            if (segment == null || !StringUtils.hasText(segment.text())) {
                continue;
            }
            List<String> tokens = tokenize(segment.text());
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) {
                tf.put(t, tf.getOrDefault(t, 0) + 1);
            }
            ChunkEntry entry = new ChunkEntry(
                    docIdSequence.getAndIncrement(),
                    courseId,
                    docId,
                    segment,
                    tokens.size(),
                    tf
            );
            index.add(entry);
        }
        log.info("[BM25] 已为课件建立内存倒排索引: courseId={}, docId={}, chunks={}",
                courseId, docId, segments.size());
    }

    /**
     * 级联清理课件对应的所有倒排索引
     *
     * @param docId 课件记录 ID
     */
    public void removeByDocId(Long docId) {
        if (docId == null) {
            return;
        }
        for (CourseIndex index : courseIndices.values()) {
            index.removeByDocId(docId);
        }
        log.info("[BM25] 课件倒排索引已清理: docId={}", docId);
    }

    /**
     * 在课程内执行 BM25 稀疏检索
     *
     * @param courseId 课程 ID（租户级过滤）
     * @param query    检索问题原文
     * @param topN     最大返回数量
     * @return 按 BM25 相似度降序排列的匹配项列表
     */
    public List<Bm25Match> search(Long courseId, String query, int topN) {
        if (courseId == null || !StringUtils.hasText(query) || topN <= 0) {
            return Collections.emptyList();
        }

        CourseIndex index = courseIndices.get(courseId);
        if (index == null || index.chunks.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        int N = index.chunks.size();
        double avgdl = index.getAvgDocLength();
        if (avgdl <= 0) {
            avgdl = 1.0;
        }

        // 计算所有候选文档的 BM25 分数
        Map<Long, Double> scores = new HashMap<>();

        for (String qTerm : queryTokens) {
            Set<Long> posting = index.inverted.get(qTerm);
            if (posting == null || posting.isEmpty()) {
                continue;
            }
            int n_q = posting.size();
            // IDF 标准公式: ln(1 + (N - n(q) + 0.5) / (n(q) + 0.5))
            double idf = Math.log(1.0 + (N - n_q + 0.5) / (n_q + 0.5));

            for (Long chunkId : posting) {
                ChunkEntry chunk = index.chunks.get(chunkId);
                if (chunk == null) {
                    continue;
                }
                int tf = chunk.termFreqs.getOrDefault(qTerm, 0);
                double numerator = tf * (K1 + 1.0);
                double denominator = tf + K1 * (1.0 - B + B * (chunk.docLength / avgdl));
                double termScore = idf * (numerator / denominator);

                scores.put(chunkId, scores.getOrDefault(chunkId, 0.0) + termScore);
            }
        }

        if (scores.isEmpty()) {
            return Collections.emptyList();
        }

        // 按得分倒序排序并截取 Top-N
        List<Map.Entry<Long, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<Bm25Match> results = new ArrayList<>();
        int limit = Math.min(topN, sorted.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<Long, Double> entry = sorted.get(i);
            ChunkEntry chunk = index.chunks.get(entry.getKey());
            if (chunk != null) {
                results.add(new Bm25Match(chunk.segment, entry.getValue()));
            }
        }

        log.debug("[BM25] 检索完成: courseId='{}', query='{}', 命中={}条", courseId, query, results.size());
        return results;
    }

    /**
     * RRF (Reciprocal Rank Fusion) 倒数排名融合算法
     *
     * 公式：Score_rrf(d) = sum_{m in {dense, sparse}} (1 / (60 + rank_m(d)))
     *
     * @param denseMatches 稠密向量召回结果列表（已按向量相似度倒序排好）
     * @param bm25Matches  稀疏 BM25 召回结果列表（已按 BM25 得分倒序排好）
     * @param topK         最终融合后截取的候选上限
     * @return 经 RRF 融合排序后的 TextSegment 列表
     */
    public List<TextSegment> fuseRrf(List<EmbeddingMatch<TextSegment>> denseMatches,
                                     List<Bm25Match> bm25Matches,
                                     int topK) {
        if ((denseMatches == null || denseMatches.isEmpty()) && (bm25Matches == null || bm25Matches.isEmpty())) {
            return Collections.emptyList();
        }

        // 用 chunk 唯一性标识（优先使用 docId_chunkIndex，无则用 text 哈希）作为聚合 key
        Map<String, TextSegment> segmentMap = new HashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        // 1. 处理 Dense 稠密路排名
        if (denseMatches != null) {
            for (int rank = 0; rank < denseMatches.size(); rank++) {
                TextSegment seg = denseMatches.get(rank).embedded();
                String key = makeSegmentKey(seg);
                segmentMap.put(key, seg);
                double rankScore = 1.0 / (RRF_K + (rank + 1));
                rrfScores.put(key, rrfScores.getOrDefault(key, 0.0) + rankScore);
            }
        }

        // 2. 处理 Sparse BM25 稀疏路排名
        if (bm25Matches != null) {
            for (int rank = 0; rank < bm25Matches.size(); rank++) {
                TextSegment seg = bm25Matches.get(rank).getSegment();
                String key = makeSegmentKey(seg);
                segmentMap.put(key, seg);
                double rankScore = 1.0 / (RRF_K + (rank + 1));
                rrfScores.put(key, rrfScores.getOrDefault(key, 0.0) + rankScore);
            }
        }

        // 3. 按 RRF 得分降序排序
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(rrfScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int limit = Math.min(topK, sorted.size());
        List<TextSegment> finalSegments = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            String key = sorted.get(i).getKey();
            finalSegments.add(segmentMap.get(key));
        }

        log.debug("[BM25-RRF] 融合完成: dense路={}, bm25路={}, 融合产出={}",
                denseMatches != null ? denseMatches.size() : 0,
                bm25Matches != null ? bm25Matches.size() : 0,
                finalSegments.size());
        return finalSegments;
    }

    /**
     * 生成文本片段的唯一去重 Key
     */
    private String makeSegmentKey(TextSegment segment) {
        if (segment == null) {
            return "";
        }
        Map<String, Object> meta = segment.metadata() != null ? segment.metadata().toMap() : Collections.emptyMap();
        Object docId = meta.get("docId");
        Object chunkIndex = meta.get("chunkIndex");
        if (docId != null && chunkIndex != null) {
            return docId + "_" + chunkIndex;
        }
        // 兜底：使用文本本身的 Hash
        return String.valueOf(segment.text().hashCode());
    }

    /**
     * 混合中英文分词器：
     * 1. 英文/数字/下划线作为完整词元（如 SO_REUSEADDR, BCrypt, 8080）；
     * 2. 中文提取单字以及相邻双字 bi-gram（如“操作系统” -> [操, 作, 系, 统, 操作, 作系, 系统]），全面覆盖专有名词。
     */
    public List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        String normalized = text.toLowerCase();

        // 1. 提取英文字符、数字与标识符
        Matcher matcher = WORD_PATTERN.matcher(normalized);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        // 2. 提取 CJK 中文字符及二元词 bi-gram
        List<Character> cjkChars = new ArrayList<>();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (isCjk(c)) {
                cjkChars.add(c);
                tokens.add(String.valueOf(c)); // 单字
            }
        }

        // 中文 bi-gram 二元滑动窗口
        for (int i = 0; i < cjkChars.size() - 1; i++) {
            tokens.add("" + cjkChars.get(i) + cjkChars.get(i + 1));
        }

        return tokens;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
}
