package com.smartqa.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@link SecurityHeadersFilter} 验收测试（Issue #94 第 1 项）。
 *
 * <p>只钉「哪些响应带了哪些头」这一件事，不做样式或安全理论讨论。
 * 两条容易退化的行为专门设了反向断言：
 * ① 上传 CSP 不得溢出到 API 响应（否则Knife4j 与将来任何 HTML 输出都会被误伤）；
 * ② HSTS 在非 prod profile 下不得出现（开发跑 http，一发 HSTS 浏览器就拒访本机）。</p>
 *
 * <p>SSE 异步链路不受本过滤器影响这件事不在这里验——
 * 它由 {@code SseWiringIntegrationTest} / {@code SseContractGuardTest} 的既有用例兜住：
 * 若过滤器在 ASYNC 派发上重复写头导致响应异常，那些用例会直接变红。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("普通 JSON 响应带四个基础安全头，且不带 uploads 的 CSP")
    void apiResponsesCarryBaselineHeaders() throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andReturn();

        var response = result.getResponse();
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"),
                "必须禁止 MIME 嗅探，否则 JSON/文本响应可能被当 HTML 执行");
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("strict-origin-when-cross-origin", response.getHeader("Referrer-Policy"));
        assertEquals("geolocation=(), microphone=(), camera=()", response.getHeader("Permissions-Policy"));

        assertNull(response.getHeader("Content-Security-Policy"),
                "uploads 专用 CSP 不应作用于 API 响应");
    }

    @Test
    @DisplayName("上传目录的响应额外带 sandbox 级 CSP（用户可控字节的同源兜底）")
    void uploadsResponsesCarrySandboxCsp() throws Exception {
        // 用一个必然不存在的文件名：关注点是响应头，不是文件内容；
        // 静态资源未命中时仍会经过过滤器（头在 chain 之前写入）。
        var result = mockMvc.perform(get("/uploads/avatars/not-exist-avatar.png")).andReturn();

        String csp = result.getResponse().getHeader("Content-Security-Policy");
        assertTrue(csp != null && csp.contains("default-src 'none'"),
                "上传响应必须锁死默认来源，实际：" + csp);
        assertTrue(csp.contains("sandbox"),
                "必须带 sandbox，确保改名上传的 HTML/脚本不会作为文档执行，实际：" + csp);
        assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options"));
    }

    @Test
    @DisplayName("非 prod profile 不得下发 HSTS（开发环境是 http）")
    void hstsDisabledOutsideProdProfile() throws Exception {
        var result = mockMvc.perform(get("/api/course/list")).andReturn();

        assertNull(result.getResponse().getHeader("Strict-Transport-Security"),
                "test profile 下 hsts-enabled 应为 false；若默认改成 true，本用例会失败并阻止误发");
    }
}
