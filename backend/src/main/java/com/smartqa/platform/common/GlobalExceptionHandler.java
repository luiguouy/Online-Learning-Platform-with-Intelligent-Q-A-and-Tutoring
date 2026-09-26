package com.smartqa.platform.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
     * 路径 / 查询参数类型不匹配 (400)
     *
     * <p>例如 {@code /api/qa/records?sessionId=abc}、{@code courseId=9999999999999999999}（超 Long 上限）。
     * 这是<b>用户把参数传错</b>，不是服务故障；不捕获会被兜底成 500，前端无法区分，
     * 且会在日志里制造大量假 ERROR 噪声。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: name={}, value={}", e.getName(), e.getValue());
        return Result.fail(400, "参数格式不正确：" + e.getName());
    }

    /**
     * 请求体不可读 (400)
     *
     * <p>覆盖 JSON 语法错误、字段类型不匹配（如 {@code {"status":"abc"}} 反序列化到 Integer）、
     * 必填体缺失等场景，统一转成可读提示而不是 500。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(400, "请求体格式不正确，请检查字段类型");
    }

    /**
     * 静态资源 / 接口路径不存在 (404)
     *
     * <p>Spring MVC 6 对未匹配的路径抛 {@link NoResourceFoundException}，
     * 若被下面的兜底 500 捕获，前端把路径拼错时只会看到"系统繁忙"，极难排查。
     * 这里明确回 404，让调用方一眼看出是路径问题。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("请求路径不存在: {}", e.getResourcePath());
        return Result.fail(404, "请求的接口或资源不存在：/" + e.getResourcePath());
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
