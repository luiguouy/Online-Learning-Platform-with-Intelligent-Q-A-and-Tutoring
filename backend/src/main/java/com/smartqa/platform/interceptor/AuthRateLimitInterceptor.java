package com.smartqa.platform.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 匿名入口与低价值写接口的限流拦截器（IP / 登录用户双维度固定窗口）。
 *
 * <p><b>为什么必须做</b>：{@code /api/auth/register} 自 PR #90 起是「匿名可写」接口，
 * 无验证码 + 无限流 = 可脚本批量灌水注册；{@code /api/auth/login} 没有失败锁定，
 * 无限流即可在线暴力破解口令；上传接口可被用来写满磁盘。</p>
 *
 * <p><b>为什么不能复用 {@link QaRateLimitInterceptor}</b>：它只挂在两个消耗大模型 Token 的
 * 问答接口上，并且<b>未登录直接回 401</b>——而登录/注册恰恰是匿名接口，复用它会把所有
 * 未登录用户挡在门外。故本类独立实现，两者互不影响。</p>
 *
 * <p><b>键的选择是这套限流的核心决策，改动前务必理解</b>：</p>
 * <ul>
 *   <li>登录 / 注册 → <b>客户端 IP</b>：此时还没有身份可用；</li>
 *   <li>上传（目前为课件）→ <b>登录用户 id</b>：全校学生常从同一 NAT 出口（机房一个 IP）出来，
 *       按 IP 限流会让整个机房共用一份额度、互相误伤，而威胁模型针对的是单个账号刷量。</li>
 * </ul>
 *
 * <p><b>IP 取值口径</b>：只用 {@code request.getRemoteAddr()}，<b>不自行解析
 * {@code X-Forwarded-For}</b>——XFF 是客户端可伪造的头，直接取用等于让攻击器随机换 IP 绕过限流。
 * 反向代理（nginx）之后由 {@code server.forward-headers-strategy=native}（见 application-prod.yml）
 * 让容器还原真实客户端地址；本地直连无需该配置。</p>
 *
 * <p>实现约束（AGENT_INSTRUCTIONS 1.3）：零新增依赖，只用 JDK 的
 * {@link ConcurrentHashMap} / {@link AtomicInteger}。</p>
 *
 * <p>响应契约与 {@link QaRateLimitInterceptor#preHandle} 一致：<b>HTTP 状态码恒 200</b>，
 * 业务码 429 写在 body 的 {@code code} 字段，字段顺序同 {@code Result}，
 * 使前端能用同一套解包逻辑处理拦截器响应与业务响应。</p>
 *
 * <p><b>已知限制（单实例）</b>：计数在 JVM 内存，多实例部署时各节点独立计数，
 * 全局额度变为 {@code 实例数 × max}。本项目课程作业单实例部署可接受；
 * 横向扩容需改用 Redis（INCR + EXPIRE）集中计数。</p>
 *
 * @author 成员 A
 */
@Component
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    /** 限流维度 */
    private enum KeyKind {
        /** 按客户端 IP（匿名接口） */
        IP,
        /** 按登录用户 id（需身份接口） */
        USER
    }

    /** 单条限流规则 */
    private record Rule(int maxRequests, long windowMillis, KeyKind keyKind, String message) {
    }

    /**
     * 限流登记表：精确路径 → 规则。
     *
     * <p>阈值口径（写成可解释的数字，而不是"看起来安全"）：</p>
     * <ul>
     *   <li>登录 10 次/分钟/IP：正常人手动输错 3-4 次就要十几秒，10 次留足余量；
     *       对脚本而言 10/min 又不足以在任何合理时间内穷举口令空间。</li>
     *   <li>注册 5 次/10 分钟/IP：真实场景是宿舍共享出口，一台设备注册 1-2 个账号，
     *       5 次已覆盖"一个宿舍一起开学注册"，同时把批量灌水速率压到 30/小时/IP。</li>
     *   <li>上传 10 次/分钟/用户：一次课件批量导入就是几次点击，10/min 不影响正常使用，
     *       但足以阻止用脚本反复写盘。</li>
     * </ul>
     */
    private static final Map<String, Rule> RULES = new LinkedHashMap<>();

    static {
        RULES.put("/api/auth/login",
                new Rule(10, 60_000L, KeyKind.IP, "登录尝试过于频繁，请稍后再试"));
        RULES.put("/api/auth/register",
                new Rule(5, 600_000L, KeyKind.IP, "注册过于频繁，请稍后再试"));
        RULES.put("/api/teacher/docs/upload",
                new Rule(10, 60_000L, KeyKind.USER, "上传过于频繁，请稍后再试"));
        // 本表必须与 SaTokenConfigure 里的 addPathPatterns 逐项对应：
        // 只写在表里而没注册路径 = 死配置；只注册而不在表里 = 预置规则不拦（因为 preHandle 查不到规则直接放过）。
        // 学生附件（/api/qa/upload/attachment）与头像（/api/user/avatar）合入 dev 时，两边同时补上。
    }

    /** 窗口数量超过该阈值时顺手清理过期条目，防止 Map 无界增长 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    /** key = 路径 + "|" + 身份（IP 或用户 id） */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        Rule rule = RULES.get(requestPath(request));
        if (rule == null) {
            // 本拦截器按精确路径注册，走到这里说明是不在登记表里的路径：直接放过，不影响任何现有接口
            return true;
        }

        String identity = switch (rule.keyKind()) {
            case IP -> request.getRemoteAddr();
            case USER -> {
                // 未登录时绝不能调 getLoginIdAsLong()（抛 NotLoginException 变 500）。
                // 鉴权已由 Sa-Token 拦截器先行负责，这里只是防御性放行。
                if (!StpUtil.isLogin()) {
                    yield null;
                }
                yield String.valueOf(StpUtil.getLoginIdAsLong());
            }
        };
        if (identity == null || identity.isBlank()) {
            return true;
        }

        String windowKey = requestPath(request) + "|" + identity;
        long now = System.currentTimeMillis();

        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().start > entry.getValue().windowMillis);
        }

        Window window = windows.computeIfAbsent(windowKey, id -> {
            Window created = new Window();
            created.start = now;
            created.windowMillis = rule.windowMillis();
            return created;
        });

        synchronized (window) {
            if (now - window.start > window.windowMillis) {
                window.start = now;
                window.windowMillis = rule.windowMillis();
                window.count.set(0);
            }
            if (window.count.incrementAndGet() > rule.maxRequests()) {
                writeJson(response, 429, rule.message());
                return false;
            }
        }
        return true;
    }

    /**
     * 取不含 context-path 的请求路径。本项目 context-path 为 "/"，
     * 但仍按 servletPath/pathInfo 规整，避免将来加前缀时限流规则整体失效。
     */
    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        int semicolon = uri.indexOf(';');
        if (semicolon >= 0) {
            uri = uri.substring(0, semicolon);
        }
        return uri;
    }

    /**
     * 直接向响应体写统一 JSON（拦截器阶段 GlobalExceptionHandler 尚未介入，
     * 必须自己保证 body 结构与全局 {@code Result} 一致）。
     *
     * <p>HTTP 状态码固定 200 的理由同 {@link QaRateLimitInterceptor#preHandle}：
     * 全项目契约是「传输层恒 200，业务码只放 body」。</p>
     */
    private void writeJson(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code
                + ",\"message\":\"" + message
                + "\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}");
    }

    /** 单身份固定窗口：窗口起点 + 窗口时长 + 窗口内计数 */
    private static final class Window {
        private volatile long start;
        private volatile long windowMillis;
        private final AtomicInteger count = new AtomicInteger(0);
    }
}
