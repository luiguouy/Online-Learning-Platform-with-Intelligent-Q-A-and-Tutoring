package com.smartqa.platform.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器。
 *
 * <p>两条铁律：</p>
 * <ol>
 *   <li>返回给前端的永远是 {@link Result} 结构，绝不把异常堆栈或原始 e.getMessage()
 *       （可能含 SQL、磁盘路径等敏感信息）直接吐给前端。</li>
 *   <li>HTTP 状态码与 body 里的 code 保持一致（与 QaRateLimitInterceptor
 *       的 401/429 写法统一），前端既可按 HTTP 状态也可按 body.code 判断。</li>
 * </ol>
 *
 * @author 成员 B
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return build(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException e) {
        log.warn("未登录访问: {}", e.getMessage());
        return build(401, "登录已过期，请重新登录");
    }

    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Result<Void>> handleNotRole(NotRoleException e) {
        log.warn("越权访问: {}", e.getMessage());
        return build(403, "无权访问该资源");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return build(400, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return build(400, "缺少必要参数：" + e.getParameterName());
    }

    /** 上传文件超过 spring.servlet.multipart.max-file-size（50MB）时触发 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传文件超限: {}", e.getMessage());
        return build(400, "文件超过 50MB 上限，请压缩后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("系统异常: ", e);
        return build(500, "系统繁忙，请稍后重试");
    }

    /**
     * 按业务 code 反查 HTTP 状态码；遇到非标准 code（如 5001）时退化为 200，
     * 保证 body 里始终是标准 Result 结构。
     */
    private ResponseEntity<Result<Void>> build(Integer code, String message) {
        HttpStatus status = HttpStatus.resolve(code == null ? 500 : code);
        if (status == null) {
            status = HttpStatus.OK;
        }
        return ResponseEntity.status(status).body(Result.fail(code, message));
    }
}
