package com.smartqa.platform.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局统一异常处理器
 * 拦截所有业务异常与未捕获异常，包装为统一 Result 结构返回，杜绝将堆栈暴露给前端
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 自定义业务异常拦截
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常拦截: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * Sa-Token 未登录异常 (401)
     */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLogin(NotLoginException e) {
        log.warn("未登录认证拦截: type={}, message={}", e.getType(), e.getMessage());
        return Result.fail(401, "登录已过期，请重新登录");
    }

    /**
     * Sa-Token 无角色/无权限异常 (403)
     */
    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRole(NotRoleException e) {
        log.warn("角色鉴权未通过: role={}", e.getRole());
        return Result.fail(403, "无权访问该资源");
    }

    /**
     * Spring Validation 参数校验异常 (400)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return Result.fail(400, msg);
    }

    /**
     * 缺少必填请求参数 (400)
     * 例如查询课件列表时漏传 courseId，若不捕获会被兜底成 500，前端无法区分"用户传错"与"服务崩了"
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必要请求参数: {}", e.getParameterName());
        return Result.fail(400, "缺少必要参数：" + e.getParameterName());
    }

    /**
     * 上传文件超过大小上限 (400)
     * 由 Spring multipart 限制触发（application.yml 的 max-file-size），统一转成可读提示
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小上限: {}", e.getMessage());
        return Result.fail(400, "文件超过 50MB 上限，请压缩后重试");
    }

    /**
     * 系统未知异常拦截 (500)
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("系统未知异常: ", e);
        return Result.fail(500, "系统繁忙，请稍后重试");
    }
}
