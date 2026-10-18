package com.smartqa.platform.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文动态压缩与多余噪音消除服务 (Context Compressor & Noise Reducer)
 *
 * 核心目标：
 * 1. 启发式消除课件中的课堂过渡废话、翻页指示、课后作业与多余空行，降低 Prompt 噪音；
 * 2. 保护「[章节导航: ...]」大纲面包屑，确保大模型对章节所属逻辑层级的准确感知；
 * 3. 句子级语义密度提炼：对长文本按句分拆，优先保留与查询词直接相关的核心陈述及相邻语境；
 * 4. 防过度修剪保底：短文本（<= 250字）或高密度定义片段保持原文，绝不误删关键算法参数或概念定义。
 */
@Service
public class ContextCompressorService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressorService.class);

    /** 课堂口头过渡套话与作业翻页噪音正则模式 */
    private static final List<Pattern> NOISE_PATTERNS = List.of(
            // 翻页/看书指引，如“请同学们翻到第25页”、“参考教材第12页”
            Pattern.compile("(?i)(请?(同学们|大家)?(翻[到至]|参考|参阅|见)(教材|课本|讲义|PPT)?第?\\s*\\d+\\s*[页张]|p\\.?\\s*\\d+)[^。！!？?\n]*[。！!？?\n]?"),
            // 课堂回顾过渡句，如“上节课我们讲了进程调度”、“上一章我们已经复习过”
            Pattern.compile("(上节课|上一章|前面|前文)我们(已经|讲过|复习了|讨论了|提到了)[^。！!？?\n]*[。！!？?\n]"),
            // 课后练习/作业/群通知指引，如“课后思考题见群公告”、“随堂练习1-3”
            Pattern.compile("(课后作业|思考题|随堂练习|课后练习|群公告|作业提交)[：:][^。！!？?\n]*[。！!？?\n]?"),
            // 纯课堂口头客套，如“好，下面我们来看”、“接下来大家注意看”
            Pattern.compile("^(好|那么|接下来|下面)，?(同学们|大家)?(请看|注意看|我们来看)[：:]?\\s*")
    );

    /** 章节导航前缀正则（必须保护） */
    private static final Pattern BREADCRUMB_PATTERN = Pattern.compile("^\\[章节导航:[^\\]]+\\]\\s*");

    /** 短文本保底阈值（小于该长度不做激进句子级裁剪，防止信息丢失） */
    private static final int SHORT_TEXT_THRESHOLD = 250;

    /** 句子切分正则（支持中文句号、感叹号、问号及换行符） */
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("([^。！!？?\n]+[。！!？?\n]?)");

    /**
     * 对检索召回的原始上下文执行动态压缩与噪音消除
     *
     * @param rawContext 召回片段拼接的原始上下文（各片段以 \n\n---\n\n 分隔）
     * @param query      用户提问或改写后的检索词
     * @return 消除噪音并提炼语义后的精炼上下文
     */
    public String compress(String rawContext, String query) {
        if (!StringUtils.hasText(rawContext)) {
            return rawContext;
        }

        // 以片段分隔符切开分别处理
        String[] chunks = rawContext.split("\n\n---\n\n");
        List<String> cleanedChunks = new ArrayList<>();

        for (String chunk : chunks) {
            String compressed = compressSingleChunk(chunk.trim(), query);
            if (StringUtils.hasText(compressed)) {
                cleanedChunks.add(compressed);
            }
        }

        if (cleanedChunks.isEmpty()) {
            return rawContext;
        }

        String result = String.join("\n\n---\n\n", cleanedChunks);
        log.debug("[ContextCompressor] 上下文压缩完成: 原始长度={}字符, 压缩后长度={}字符",
                rawContext.length(), result.length());
        return result;
    }

    /**
     * 针对单个切块执行清洗与句子级精炼
     */
    private String compressSingleChunk(String chunk, String query) {
        if (!StringUtils.hasText(chunk)) {
            return "";
        }

        // 1. 提取并保留 [章节导航: ...] 前缀
        String breadcrumbPrefix = "";
        String body = chunk;
        Matcher breadcrumbMatcher = BREADCRUMB_PATTERN.matcher(chunk);
        if (breadcrumbMatcher.find()) {
            breadcrumbPrefix = breadcrumbMatcher.group();
            body = chunk.substring(breadcrumbMatcher.end()).trim();
        }

        // 2. 启发式消除课堂过渡噪音
        String denoisyBody = removeClassroomNoise(body);

        // 3. 句子级长文本语义精炼
        String refinedBody;
        if (denoisyBody.length() <= SHORT_TEXT_THRESHOLD) {
            // 短文本或定义型核心段落，直接保留全文保真
            refinedBody = denoisyBody;
        } else {
            refinedBody = filterSentencesByRelevance(denoisyBody, query);
        }

        // 若精炼后内容异常为空，回退使用清洗后的主体
        if (!StringUtils.hasText(refinedBody)) {
            refinedBody = StringUtils.hasText(denoisyBody) ? denoisyBody : body;
        }

        if (StringUtils.hasText(breadcrumbPrefix)) {
            return breadcrumbPrefix + "\n" + refinedBody.trim();
        }
        return refinedBody.trim();
    }

    /**
     * 移除课堂废话与模板噪音
     */
    public String removeClassroomNoise(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        String cleaned = text;
        for (Pattern pattern : NOISE_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceAll("");
        }

        // 清理连续多余换行符
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n").trim();
        return cleaned;
    }

    /**
     * 基于与 Query 的相关度打分保留核心句群（包含匹配句及其紧邻上下文句）
     */
    private String filterSentencesByRelevance(String text, String query) {
        List<String> sentences = extractSentences(text);
        if (sentences.size() <= 2) {
            return text;
        }

        Set<String> queryKeywords = extractKeywords(query);
        boolean[] keep = new boolean[sentences.size()];
        boolean anyMatched = false;

        for (int i = 0; i < sentences.size(); i++) {
            String s = sentences.get(i).trim();
            if (s.isEmpty()) {
                continue;
            }

            // 检查句子是否命中关键词
            int hitCount = 0;
            for (String kw : queryKeywords) {
                if (s.toLowerCase().contains(kw.toLowerCase())) {
                    hitCount++;
                }
            }

            // 若命中关键词或首句为定义句（含“是”、“指”、“包括”、“定义”等高密词）
            if (hitCount > 0 || (i == 0 && isDefinitionSentence(s))) {
                keep[i] = true;
                anyMatched = true;
                // 同时保留前一句和后一句，维护语境语义完整度
                if (i > 0) keep[i - 1] = true;
                if (i + 1 < sentences.size()) keep[i + 1] = true;
            }
        }

        // 若没有任何句子显式匹配关键词，保守保留原文
        if (!anyMatched) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (keep[i]) {
                sb.append(sentences.get(i));
            }
        }
        return sb.toString().trim();
    }

    private List<String> extractSentences(String text) {
        List<String> list = new ArrayList<>();
        Matcher m = SENTENCE_SPLIT_PATTERN.matcher(text);
        while (m.find()) {
            list.add(m.group());
        }
        return list;
    }

    private Set<String> extractKeywords(String query) {
        if (!StringUtils.hasText(query)) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        // 提取连续英文单词与数字
        Matcher m = Pattern.compile("[a-zA-Z0-9_]{2,}").matcher(query);
        while (m.find()) {
            set.add(m.group().toLowerCase());
        }
        // 提取中文字符串并生成 2-gram 关键词（滑动窗口），同时滤除单字
        Matcher chineseMatcher = Pattern.compile("[\\u4e00-\\u9fa5]+").matcher(query);
        while (chineseMatcher.find()) {
            String cn = chineseMatcher.group();
            if (cn.length() == 2) {
                set.add(cn);
            } else if (cn.length() > 2) {
                for (int i = 0; i <= cn.length() - 2; i++) {
                    set.add(cn.substring(i, i + 2));
                }
            }
        }
        // 移除常见无区分度的中文双字虚词与疑问词
        set.removeAll(Set.of("什么", "怎么", "如何", "为何", "特点", "概述", "简述", "介绍", "属于", "哪个", "哪些", "可以"));
        return set;
    }

    private boolean isDefinitionSentence(String sentence) {
        return sentence.contains("是指") || sentence.contains("主要包括")
                || sentence.contains("定义为") || sentence.contains("用于解决");
    }
}
