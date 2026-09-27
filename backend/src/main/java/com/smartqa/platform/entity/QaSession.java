package com.smartqa.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 智能问答会话实体（对应表 {@code qa_session}）。
 *
 * <p>会话由成员 A 在收到 {@code sessionId=0} 时通过
 * {@code QaSessionService.createSessionLazy} 懒创建。</p>
 *
 * @author 成员 B
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("qa_session")
@Schema(description = "智能问答会话")
public class QaSession implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "会话ID")
    private Long id;

    @Schema(description = "提问学生ID")
    private Long userId;

    @Schema(description = "关联课程ID")
    private Long courseId;

    @Schema(description = "会话标题（懒创建时取问题前 15 字符）")
    private String sessionTitle;

    @TableLogic
    @Schema(description = "逻辑删除：0-正常，1-删除")
    private Integer isDeleted;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "最后活跃时间，列表按它倒序")
    private LocalDateTime updatedAt;
}
