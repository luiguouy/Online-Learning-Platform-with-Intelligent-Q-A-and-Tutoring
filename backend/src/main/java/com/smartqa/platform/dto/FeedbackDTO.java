package com.smartqa.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 点赞 / 点踩请求体。
 *
 * <p>⚠️ 字段名必须是 {@code status}（前端已按此写死），取值只能是 1 或 -1，
 * 其余值由 Service 层抛 400 参数错误。</p>
 *
 * @author 成员 B
 */
@Data
@Schema(description = "问答反馈请求")
public class FeedbackDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "反馈状态不能为空")
    @Schema(description = "1-点赞，-1-点踩", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer status;
}
