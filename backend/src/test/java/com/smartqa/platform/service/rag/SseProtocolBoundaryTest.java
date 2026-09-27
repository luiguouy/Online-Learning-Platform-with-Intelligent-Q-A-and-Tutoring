package com.smartqa.platform.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE question 查询串「业务上限」真实协议栈回归测试（PR#41 审查 H1 修复配套）。
 *
 * <p>SseWiringIntegrationTest 走 MockMvc：参数直塞 parameterMap，<b>完全绕过</b>
 * Tomcat {@code Http11InputBuffer.parseRequestLine}，覆盖不到「请求真正经协议层进来后
 * 能否拿到友好业务码」这条链路。本类以 RANDOM_PORT + TestRestTemplate 起<b>真实 HTTP 栈</b>，
 * 端到端锁定 B3.1「超长提问从无信息断连改为 event:error {errorCode:4000} 友好返回」：</p>
 * <ol>
 *   <li>1800 字（略超业务上限 {@code MAX_QUESTION_LENGTH=1600}）→ 真实 HTTP 进到 Controller → SSE {@code error 4000}；</li>
 *   <li>2200 字（远超业务上限）→ 同样进到 Controller → SSE {@code error 4000}，证明现实中可达的超长
 *       提问都落进友好返回区间，不会退化成无信息断连。</li>
 * </ol>
 *
 * <p><b>为何不断言「协议上限外返回 431」</b>：本机实测（Tomcat 10.1，{@code max-http-request-header-size=64KB}）
 * 的请求行上限远高于早前估计——2200 字（≈20KB 请求行）仍能进到 Controller，直到约 72KB 才在容器层被拒，
 * 且返回 <b>400 而非 431</b>。该阈值与状态码随 Tomcat 版本/配置漂移，硬钉它既脆弱又是在测容器而非本项目代码。
 * 因此本类只断言<b>稳定的业务层友好返回</b>；协议层的具体天花板交由部署环境的容器配置决定。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SseProtocolBoundaryTest {

    /** 测试课程 ID：与 seed（1~3）隔离；两条用例都在推流开始前终止，不落库 */
    private static final long COURSE_ID = 888888L;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    /** RANDOM_PORT 实际监听端口：用于拼绝对 URI，避免相对路径被解析成 //api 网络路径引用 */
    @LocalServerPort
    private int port;

    private HttpHeaders authHeaders;

    @BeforeEach
    void login() throws Exception {
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> login = rest.postForEntity("/api/auth/login",
                new HttpEntity<>("{\"username\":\"student02\",\"password\":\"123456\"}", jsonHeaders),
                String.class);
        JsonNode body = objectMapper.readTree(login.getBody());
        assertEquals(200, body.path("code").asInt(), "测试前提：student02 真实 HTTP 登录成功");
        authHeaders = new HttpHeaders();
        authHeaders.set("Authorization", "Bearer " + body.path("data").path("token").asText());
    }

    @Test
    @DisplayName("1800 字提问（超业务上限、协议预算内）真实 HTTP 能进到 Controller，error 4000 友好返回")
    void overBusinessLimitWithinProtocol() throws Exception {
        ResponseEntity<String> resp = streamChat("虚".repeat(1800));

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "该长度应能进到 Controller（若变 4xx 说明请求行预算被改动）");
        String body = resp.getBody();
        assertNotNull(body, "SSE error 事件应有响应体");
        JsonNode error = dataOfEvent(body, "error");
        assertEquals(4000, error.path("errorCode").asInt(),
                "超长提问应拿到业务参数错误码 4000，实际报文：" + body);
        assertTrue(error.path("message").asText().contains("过长"),
                "错误消息应可读，实际：" + error);
    }

    @Test
    @DisplayName("2200 字提问（远超业务上限、仍在协议预算内）真实 HTTP 进到 Controller，error 4000 友好返回")
    void farOverBusinessLimitStillFriendly() throws Exception {
        ResponseEntity<String> resp = streamChat("虚".repeat(2200));

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "2200 字（≈20KB 请求行）应仍在协议预算内进到 Controller，实际状态：" + resp.getStatusCode());
        String body = resp.getBody();
        assertNotNull(body, "SSE error 事件应有响应体");
        JsonNode error = dataOfEvent(body, "error");
        assertEquals(4000, error.path("errorCode").asInt(),
                "远超业务上限的提问仍应拿到友好业务码 4000，实际报文：" + body);
        assertTrue(error.path("message").asText().contains("过长"),
                "错误消息应可读，实际：" + error);
    }

    /** 以真实 HTTP GET 发起 SSE 请求（UTF-8 百分号编码，与浏览器一致：每个 UTF-8 字节编成 %XX 三字节） */
    private ResponseEntity<String> streamChat(String question) {
        // 必须拼绝对 URI（含 scheme+host+port）：若用相对路径 "/api/..."，TestRestTemplate 与 rootUri
        // 拼接后会得到 "//api/qa/chat/stream" —— 双斜杠开头被 URI 解析成 authority=api 的网络路径引用，
        // 服务端识别为畸形路径直接回「非法请求」响应体，两条断言全废（实测踩坑）。
        URI uri = UriComponentsBuilder.fromUriString("http://localhost:" + port + "/api/qa/chat/stream")
                .queryParam("courseId", COURSE_ID)
                .queryParam("sessionId", 0)
                .queryParam("question", question)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
        return rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(authHeaders), String.class);
    }

    /** 提取指定 SSE 事件的 data 行并解析为 JSON */
    private JsonNode dataOfEvent(String body, String eventName) throws Exception {
        String[] lines = body.split("\r?\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].trim().equals("event:" + eventName) && lines[i + 1].startsWith("data:")) {
                return objectMapper.readTree(lines[i + 1].substring("data:".length()).trim());
            }
        }
        throw new AssertionError("未找到事件 " + eventName + "，完整报文：" + body);
    }
}
