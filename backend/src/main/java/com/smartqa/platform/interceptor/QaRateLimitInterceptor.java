package com.smartqa.platform.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 答疑 / 知识点接口限流拦截器（按用户维度固定窗口计数）。
 *
 * <p>为什么必须做：答疑接口每次调用都消耗大模型 Token（Embedding + 生成），
 * 公开演示或恶意重复提问会在几分钟内烧光额度。</p>
 *
 * <p>实现约束（AGENT_INSTRUCTIONS 1.3）：零新增依赖，只用 JDK 的
 * {@link ConcurrentHashMap} / {@link AtomicInteger}，禁止引入 Guava。</p>
 *
 * <p>注册位置见 {@code SaTokenConfigure}，只拦截
 * {@code /api/qa/chat/stream} 与 {@code /api/knowledge/generate}，
 * 不做全局拦截（全局会误伤登录与课件上传）。</p>
 *
 * @author 成员 B
 */
@Component
public class QaRateLimitInterceptor implements HandlerInterceptor {

    /** 限流规则：每用户每 60 秒最多 20 次提问 */
    private static final int MAX_REQUESTS = 20;
    private static final long WINDOW_MILLIS = 60_000L;
    /** 窗口数量超过该阈值时顺手清理过期条目，防止 Map 无界增长 */
    private static final int CLEANUP_THRESHOLD = 1000;

    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // 关键：未登录时绝不能调用 getLoginIdAsLong()，否则抛 NotLoginException 变成 500。
        if (!StpUtil.isLogin()) {
            writeJson(response, 401, "请先登录");
            return false;
        }

        Long userId = StpUtil.getLoginIdAsLong();
        long now = System.currentTimeMillis();

        // 演示环境用户数极少，正常不会触达；触达时顺手清掉超过一个窗口未活动的条目
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().start > WINDOW_MILLIS);
        }

        Window window = windows.computeIfAbsent(userId, id -> {
            Window created = new Window();
            created.start = now;
            return created;
        });

        synchronized (window) {
            if (now - window.start > WINDOW_MILLIS) {
                window.start = now;
                window.count.set(0);
            }
            if (window.count.incrementAndGet() > MAX_REQUESTS) {
                // 字段名必须是 message，与统一响应 Result.message 保持一致
                writeJson(response, 429, "提问过于频繁，请稍后再试");
                return false;
            }
        }
        return true;
    }

    /**
     * 直接向响应体写统一 JSON（拦截器阶段 GlobalExceptionHandler 尚未介入，
     * 必须自己保证 body 结构与全局 {@code Result} 一致：{@code {code, message, data}}）。
     *
     * <p><b>HTTP 状态码固定 200</b>：全项目契约是「传输层恒 200，业务码只放 body」，
     * 此处若返回真实 401/429，前端（成员 C）按 body 取 {@code code} 的解析逻辑
     * 会被网络层的错误分支抢先打断。业务码仍写在 body 的 {@code code} 字段。</p>
     *
     * <p>字段与顺序同 {@code Result} 一致（code / message / data / timestamp），
     * 使前端能用同一套解包逻辑处理拦截器响应与业务响应。</p>
     */
    private void writeJson(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code
                + ",\"message\":\"" + message
                + "\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}");
    }

    /** 单用户固定窗口：窗口起点 + 窗口内计数 */
    private static final class Window {
        private volatile long start;
        private final AtomicInteger count = new AtomicInteger(0);
    }
}
