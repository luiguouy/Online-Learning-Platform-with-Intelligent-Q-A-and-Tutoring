package com.smartqa.platform.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头过滤器（Issue #94 第 1 项）。
 *
 * <p><b>为什么放在后端</b>：后端直接出网的响应有两类真正需要兜底——
 * ① 普通 JSON 响应（避免被浏览器嗅探成别的内容类型）；
 * ② {@code /uploads/**} 静态映射出的<strong>用户上传文件</strong>（头像、附件），
 * 这些是<b>用户可控字节</b>且<strong>同源可访</strong>，是最典型的存储型 XSS 载体。</p>
 *
 * <p><b>不作用于前端 SPA</b>：学生端/教师端由 Vite dev server（开发）或 nginx（部署）提供，
 * 面向应用的 CSP 属于部署层配置，写在后端响应上无效也不合适。
 * 前端 CSP 的落地位置与限制记在 {@code dev-docs/SECURITY_DECISIONS.md} 的 SEC-5。</p>
 *
 * <p><b>为什么不给 {@code /doc.html} 加 CSP</b>：Knife4j 文档台大量使用内联脚本，
 * 严格 CSP 会直接把开发期调试台弄坏，而它已在 prod profile 整体关闭（SEC-4），
 * 风险窗口只在开发环境，属可接受残余风险。</p>
 *
 * <p>零新增依赖（AGENT_INSTRUCTIONS 1.3），只做 header 写入。</p>
 *
 * @author 成员 A
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** 上传子目录前缀：这些响应承载用户上传的字节，需要额外的"禁止执行"约束 */
    private static final String UPLOADS_PREFIX = "/uploads/";

    /**
     * 上传文件的 CSP：只允许自身来源的图片/样式，并用 {@code sandbox} 关掉文档能力。
     *
     * <p>即便有人把 HTML/脚本改名为 {@code .png} 上传（扩展名白名单允许图片），
     * 浏览器拿到的是 {@code image/png} + {@code nosniff} + sandbox，
     * 不会作为文档解析执行，同源 XSS 这条路就被堵住。
     * 对正常的 {@code <img src>} 引用没有副作用：CSP 文档级指令不影响它作为子资源被加载。</p>
     */
    private static final String UPLOAD_CSP =
            "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; sandbox";

    /** HSTS 仅在 prod profile 打开（application-prod.yml 里置 true）：开发用 http，开了会把本机打不开 */
    @Value("${app.security.hsts-enabled:false}")
    private boolean hstsEnabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        // SSE（/api/qa/chat/stream）是异步请求：ASYNC 二次派发时响应早已提交，
        // 再写 header 既无效也可能触发 IllegalStateException。只在首次 REQUEST 派发上处理。
        if (request.getDispatcherType() == DispatcherType.REQUEST && !response.isCommitted()) {
            applyHeaders(request, response);
        }
        filterChain.doFilter(request, response);
    }

    private void applyHeaders(HttpServletRequest request, HttpServletResponse response) {
        // 拒绝 MIME 嗅探：上传文件与 JSON 响应都不许被"猜"成 HTML 执行
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 平台不需要被任何站点内嵌（包括自己），点击劫持直接堵死
        response.setHeader("X-Frame-Options", "DENY");
        // 跨源跳转不把完整路径（含 courseId / sessionId 这类资源标识）带给第三方
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        // 后端接口不需要任何设备能力；教师端/学生端的录音定位等一概不授权
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");

        if (isUploadsPath(request)) {
            response.setHeader("Content-Security-Policy", UPLOAD_CSP);
        }

        if (hstsEnabled) {
            // 只在 HTTPS 部署下启用；includeSubDomains 需谨慎，故只覆盖本站
            response.setHeader("Strict-Transport-Security", "max-age=31536000");
        }
    }

    /** 只按路径前缀判断，不用 contextPath 拼接：本项目 context-path 为 "/" */
    private boolean isUploadsPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.startsWith(UPLOADS_PREFIX);
    }
}
