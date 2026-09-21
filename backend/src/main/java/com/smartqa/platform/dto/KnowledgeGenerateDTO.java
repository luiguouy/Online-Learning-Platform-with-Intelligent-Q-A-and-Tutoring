package com.smartqa.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 知识点解析请求体（A2.5）。
 *
 * <p>契约见 {@code TEAM_WORK_DIVISION.md} 第三章接口矩阵：
 * {@code POST /api/knowledge/generate}，入参 = 课程 ID + 知识点名称。</p>
 *
 * @author 成员 A
 */
@Data
@Schema(description = "知识点解析请求")
public class KnowledgeGenerateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "课程ID不能为空")
    @Schema(description = "关联课程ID（检索范围限定在本课课件）", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long courseId;

    @NotBlank(message = "知识点名称不能为空")
    @Size(max = 100, message = "知识点名称过长")
    @Schema(description = "知识点名称", example = "页面置换算法LRU与FIFO对比", requiredMode = Schema.RequiredMode.REQUIRED)
    private String knowledgePoint;
}
