package com.smartqa.platform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 问答上传附件返回对象（图片/文档）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "问答上传附件结果")
public class AttachmentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "附件可访问URL")
    private String fileUrl;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "附件类型：image | doc")
    private String fileType;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "如果是文档，提取出的前置文本内容摘要")
    private String extractedText;
}
