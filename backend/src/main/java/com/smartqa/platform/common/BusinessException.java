package com.smartqa.platform.common;

import lombok.Getter;

/**
 * 统一自定义业务异常
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 业务错误码
     */
    private final Integer code;

    /**
     * 默认 400 业务错误
     *
     * @param message 错误描述
     */
    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    /**
     * 自定义错误码构造
     *
     * @param code    错误码
     * @param message 错误描述
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
