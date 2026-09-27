package com.smartqa.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.smartqa.platform.vo.SseReferenceVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能问答明细记录实体（对应表 {@code qa_record}）。
 *
 * <p>纯只读记录表：功能范围已冻结，不做人工纠偏，
 * 因此没有 is_corrected / corrected_answer / teacher_comment 字段。</p>
 *
 * <p>⚠️ {@code groundingReferences} 是 JSON 列，必须同时满足两点才能与
 * {@code List<SseReferenceVO>} 互转，缺一不可：</p>
 * <ol>
 *   <li>{@code @TableName(autoResultMap = true)}；</li>
 *   <li>字段上 {@code @TableField(typeHandler = JacksonTypeHandler.class)}。</li>
 * </ol>
 * 少任何一个，查询返回的 {@code groundingReferences} 都会是 null。
 *
 * @author 成员 B
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "qa_record", autoResultMap = true)
@Schema(description = "智能问答明细记录")
public class QaRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 未评价 */
    public static final Integer FEEDBACK_NONE = 0;
    /** 点赞 */
    public static final Integer FEEDBACK_UP = 1;
    /** 点踩 */
    public static final Integer FEEDBACK_DOWN = -1;

    @TableId(type = IdType.AUTO)
    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "所属会话ID")
    private Long sessionId;

    @Schema(description = "提问学生ID")
    private Long userId;

    @Schema(description = "所属课程ID")
    private Long courseId;

    @Schema(description = "学生提问内容")
    private String question;

    @Schema(description = "AI 生成的 Markdown 回答")
    private String answer;

    /**
     * 命中的课件出处快照（JSON 数组）。
     * 元素结构同 {@link SseReferenceVO}，与成员 A 的 SSE references 事件载荷共用契约。
     */
    @TableField(value = "grounding_references", typeHandler = JacksonTypeHandler.class)
    @Schema(description = "命中的课件出处快照（JSON 数组）")
    private List<SseReferenceVO> groundingReferences;

    @Schema(description = "学生打分：1-点赞，-1-点踩，0-未评")
    private Integer feedbackRating;

    @Schema(description = "模型生成耗时（毫秒）")
    private Integer latencyMs;

    @TableLogic
    @Schema(description = "逻辑删除：0-正常，1-删除")
    private Integer isDeleted;

    @Schema(description = "提问时间")
    private LocalDateTime createdAt;
}
