package com.smartqa.platform.common;

import lombok.Getter;

/**
 * 业务异常。必须有 code 字段与 getCode()，否则 GlobalExceptionHandler 无法编译。
 *
 * @author 成员 B
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Integer code;

    /** 默认 400 业务错误 */
    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    /** 自定义错误码（如 401 / 403 / 404 / 429） */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
