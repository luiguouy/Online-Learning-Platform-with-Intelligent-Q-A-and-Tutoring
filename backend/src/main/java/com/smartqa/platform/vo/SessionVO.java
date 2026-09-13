package com.smartqa.platform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话列表项。只暴露前端需要的字段，不外泄 isDeleted 等内部列。
 *
 * @author 成员 B
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "问答会话列表项")
public class SessionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "会话 ID")
    private Long id;

    @Schema(description = "所属课程 ID")
    private Long courseId;

    @Schema(description = "会话标题（懒创建时取问题前 15 字符）")
    private String sessionTitle;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后活跃时间，列表按它倒序")
    private LocalDateTime updatedAt;
}
