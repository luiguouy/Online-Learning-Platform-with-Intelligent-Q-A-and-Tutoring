package com.smartqa.platform.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一 REST 响应体封装
 *
 * @param <T> 数据载荷类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一响应包装结构")
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "响应状态码：200成功，400业务异常，401未登录，403无权限，500系统繁忙", example = "200")
    private Integer code;

    @Schema(description = "响应信息描述", example = "操作成功")
    private String message;

    @Schema(description = "响应数据对象")
    private T data;

    @Schema(description = "时间戳 (毫秒)", example = "1726135200000")
    private Long timestamp;

    public static <T> Result<T> success(T data) {
        return Result.<T>builder()
                .code(200)
                .message("操作成功")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return Result.<T>builder()
                .code(code)
                .message(message)
                .data(null)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> Result<T> fail(String message) {
        return fail(400, message);
    }
}
