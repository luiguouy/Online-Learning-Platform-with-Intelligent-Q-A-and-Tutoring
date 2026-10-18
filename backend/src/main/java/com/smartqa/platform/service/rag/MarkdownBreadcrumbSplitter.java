package com.smartqa.platform.service.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown 结构感知与层级面包屑切块器 (Markdown Breadcrumb Splitter)
 *
 * 核心目标：
 * 1. 识别 Markdown 文档中的标题层级（#、##、### 等），以标题结构作为自然逻辑切分边界；
 * 2. 动态维护标题层级栈，生成形如「第一章 操作系统 > 1.2 进程管理 > 进程状态转换」的面包屑路径；
 * 3. 将面包屑注入切块的 Metadata 以及正文头部，赋能本地向量检索感知章节上下文，防止知识点断章取义；
 * 4. 对于超长章节小节，平滑在当前面包屑下进行二次段落分块；若全文无标题，则优雅回退至通用分块。
 */
@Component
public class MarkdownBreadcrumbSplitter {

    private static final Logger log = LoggerFactory.getLogger(MarkdownBreadcrumbSplitter.class);

    /** 匹配 Markdown ATX 标题行：行首 1~6 个 #，紧跟空白字符与标题正文 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    /**
     * 表示标题层级栈中的一项
     */
    private static class HeadingEntry {
        final int level;
        final String title;

        HeadingEntry(int level, String title) {
            this.level = level;
            this.title = title;
        }
    }

    /**
     * 对 Markdown 文本执行结构感知与面包屑切块
     *
     * @param markdownText 原始 Markdown 文本
     * @param maxChunkSize 单块最大字符数（参考 chunk.size 配置）
     * @param overlap      块间重叠字符数（参考 chunk.overlap 配置）
     * @return 切块后的 TextSegment 列表；若文本无标题则返回空列表（调用方可据次回退）
     */
    public List<TextSegment> split(String markdownText, int maxChunkSize, int overlap) {
        if (!StringUtils.hasText(markdownText)) {
            return List.of();
        }

        String[] lines = markdownText.split("\r?\n");
        // 预检：全文是否至少包含一个有效标题
        boolean hasHeading = false;
        for (String line : lines) {
            if (HEADING_PATTERN.matcher(line.trim()).matches()) {
                hasHeading = true;
                break;
            }
        }

        if (!hasHeading) {
            log.debug("[MarkdownSplitter] 文档未包含有效 Markdown 标题，建议回退至默认分块");
            return List.of();
        }

        List<TextSegment> resultSegments = new ArrayList<>();
        List<HeadingEntry> headingStack = new ArrayList<>();
        StringBuilder currentSectionContent = new StringBuilder();

        for (String line : lines) {
            String trimmedLine = line.trim();
            Matcher matcher = HEADING_PATTERN.matcher(trimmedLine);

            if (matcher.matches()) {
                // 遇到新标题：先将前面小节累积的正文切块归档
                flushSection(resultSegments, headingStack, currentSectionContent, maxChunkSize, overlap);

                int level = matcher.group(1).length();
                String title = matcher.group(2).trim();

                // 弹出栈中同级或更深层级的旧标题（如当前是 ##，则弹出之前的所有 ## 与 ###）
                while (!headingStack.isEmpty() && headingStack.get(headingStack.size() - 1).level >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }

                // 压入当前标题
                headingStack.add(new HeadingEntry(level, title));

            } else {
                // 普通正文行，保留换行追加
                currentSectionContent.append(line).append("\n");
            }
        }

        // 处理最后一个小节的内容
        flushSection(resultSegments, headingStack, currentSectionContent, maxChunkSize, overlap);

        log.info("[MarkdownSplitter] Markdown 结构感知切块完成，共生成 {} 个片段", resultSegments.size());
        return resultSegments;
    }

    /**
     * 将当前小节内容生成一个或多个带有面包屑的 TextSegment
     */
    private void flushSection(List<TextSegment> resultSegments,
                              List<HeadingEntry> headingStack,
                              StringBuilder currentSectionContent,
                              int maxChunkSize,
                              int overlap) {
        String content = currentSectionContent.toString().trim();
        // 清空当前内容缓冲，供下一小节使用
        currentSectionContent.setLength(0);

        if (content.isEmpty()) {
            return;
        }

        String breadcrumb = headingStack.stream()
                .map(h -> h.title)
                .collect(Collectors.joining(" > "));

        // 若当前小节内容在合理长度内，直接作为单块
        if (content.length() <= maxChunkSize) {
            resultSegments.add(buildSegment(content, breadcrumb));
        } else {
            // 超长小节：使用通用递归分块对正文二次拆分，但每个子块都保留相同的面包屑
            Document doc = Document.from(content);
            List<TextSegment> subSegments = DocumentSplitters.recursive(maxChunkSize, overlap).split(doc);
            for (TextSegment sub : subSegments) {
                resultSegments.add(buildSegment(sub.text(), breadcrumb));
            }
        }
    }

    /**
     * 构造携带面包屑前缀与元数据的 TextSegment
     */
    private TextSegment buildSegment(String text, String breadcrumb) {
        Metadata metadata = new Metadata();
        if (StringUtils.hasText(breadcrumb)) {
            metadata.put("breadcrumb", breadcrumb);
            // 在切块正文头部显式附加面包屑路径标签，增强稠密向量检索与大模型上下文感知
            String enrichedText = String.format("[章节导航: %s]\n%s", breadcrumb, text);
            return TextSegment.from(enrichedText, metadata);
        } else {
            return TextSegment.from(text, metadata);
        }
    }
}
