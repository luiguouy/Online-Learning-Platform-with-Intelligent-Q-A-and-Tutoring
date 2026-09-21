package com.smartqa.platform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 知识点解析响应 VO（A2.5）。
 *
 * <p>返回结构化 Markdown 精解，前端（成员 C）以只读 Markdown 面板渲染，
 * <b>不含自测题</b>（本期功能范围已裁掉答题交互，见 {@code THREE_WEEK_PLAN.md} 第一节）。</p>
 *
 * @author 成员 A
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "知识点解析结果")
public class KnowledgeGenerateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "关联课程ID", example = "1")
    private Long courseId;

    @Schema(description = "知识点名称", example = "页面置换算法LRU与FIFO对比")
    private String knowledgePoint;

    @Schema(description = "结构化 Markdown 精解（核心概念定义 + 难点辨析）")
    private String content;
}
