package com.smartqa.platform.interceptor;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuthRateLimitInterceptor} 单元测试。
 *
 * <p><b>只测 IP 维度规则</b>（登录/注册）：USER 维度会调用 {@code StpUtil}，需要 Sa-Token
 * 的请求上下文，纯单测里拿不到（这正是 {@code QaRateLimitInterceptor} 没有单测的原因）。
 * 因此本类的断言聚焦在"限流是否真的挡住超额请求"与"响应契约是否仍是 HTTP 200 + body 业务码"，
 * 后者是本项目最容易在改限流时被破坏的约定。</p>
 *
 * @author 成员 A
 */
class AuthRateLimitInterceptorTest {

    private static final String LOGIN = "/api/auth/login";
    private static final String REGISTER = "/api/auth/register";

    /** 每个用例一个新实例，避免窗口计数在用例间串味 */
    private final AuthRateLimitInterceptor interceptor = new AuthRateLimitInterceptor();

    private MockHttpServletRequest request(String uri, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setContextPath("");
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    @DisplayName("登录：同一 IP 前 10 次放行，第 11 次拦截")
    void loginBlocksAfterTenPerMinute() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 1; i <= 10; i++) {
            assertTrue(interceptor.preHandle(request(LOGIN, "203.0.113.7"), response, null),
                    "第 " + i + " 次登录请求应被放行");
        }

        MockHttpServletResponse blockedResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request(LOGIN, "203.0.113.7"), blockedResponse, null),
                "第 11 次登录请求应被限流拦截");
    }

    @Test
    @DisplayName("登录：不同 IP 各自独立计数（同校 NAT 场景不能互相误伤）")
    void differentIpHasOwnQuota() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 0; i < 10; i++) {
            interceptor.preHandle(request(LOGIN, "203.0.113.1"), response, null);
        }
        // 换 IP 后额度重新可用
        assertTrue(interceptor.preHandle(request(LOGIN, "203.0.113.2"), response, null),
                "另一个 IP 不应被前一 IP 的额度拖累");
    }

    @Test
    @DisplayName("注册：5 次/10 分钟/IP，第 6 次拦截（PR #90 匿名可写接口的直接缺口）")
    void registerBlocksAfterFivePerTenMinutes() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 1; i <= 5; i++) {
            assertTrue(interceptor.preHandle(request(REGISTER, "198.51.100.9"), response, null),
                    "第 " + i + " 次注册请求应被放行");
        }

        assertFalse(interceptor.preHandle(request(REGISTER, "198.51.100.9"), response, null),
                "第 6 次注册请求应被限流拦截");
    }

    @Test
    @DisplayName("限流响应契约：HTTP 恒 200，业务码 429 只放 body（前端按 body 解包）")
    void rateLimitedResponseKeepsProjectContract() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 10; i++) {
            interceptor.preHandle(request(LOGIN, "203.0.113.66"), response, null);
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        interceptor.preHandle(request(LOGIN, "203.0.113.66"), blocked, null);

        assertEquals(HttpServletResponse.SC_OK, blocked.getStatus(),
                "传输层状态码必须仍是 200，否则前端错误分支会抢在业务解包之前打断");
        assertEquals("application/json;charset=UTF-8", blocked.getContentType());

        String body = blocked.getContentAsString();
        assertTrue(body.contains("\"code\":429"), "业务码 429 必须写在 body.code：" + body);
        assertTrue(body.contains("登录尝试过于频繁"), "文案应给出可行动提示：" + body);
        // 字段名与顺序同 Result：code / message / data / timestamp
        assertTrue(body.startsWith("{\"code\":"), "body 结构应与统一 Result 一致：" + body);
        assertTrue(body.contains("\"data\":null"), "应带 data 字段供前端统一解包：" + body);
        assertTrue(body.contains("\"timestamp\":"), "应带 timestamp 字段：" + body);
    }

    @Test
    @DisplayName("未在登记表中的路径直接放过，不影响任何现有接口")
    void unregisteredPathPassesThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 0; i < 50; i++) {
            assertTrue(interceptor.preHandle(request("/api/course/list", "203.0.113.88"), response, null),
                    "非限流路径不应被本拦截器影响");
        }
        assertEquals(200, response.getStatus());
    }
}
