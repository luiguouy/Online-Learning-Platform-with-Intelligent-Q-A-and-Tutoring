package com.smartqa.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SSE 卡死防护验收（Issue #98）。
 *
 * <p><b>被测问题</b>：上游模型排队/无响应时，{@code onNext}、{@code onComplete}、
 * {@code onError} 一个都不会被调用（langchain4j 0.35 流式模型的 {@code timeout-seconds}
 * 落到 okhttp 只是<strong>两次读取之间</strong>的 read-timeout，"慢但一直有字节"时不触发）。
 * 旧实现里唯一的收尾是 {@code SseEmitter} 超时，而它又没注册 {@code onTimeout}，
 * 于是学生端表现为无限转圈且收不到任何原因。</p>
 *
 * <p><b>本测试怎么做到确定性</b>：用不回调的假模型（不依赖真实上游抽风），
 * 配合 test profile 把 {@code rag.llm.first-token-timeout-seconds} 压到 2 秒，
 * 于是首字节看门狗必然在 2s 触发。</p>
 *
 * <p><b>没覆盖什么（不假装有）</b>：控制器里 {@code emitter.onTimeout} 那条兜底分支依赖
 * <strong>Servlet 容器</strong>的异步超时派发，MockMvc 的异步实现不会触发它，因此本类不测它，
 * 该分支由端到端真实 HTTP 栈验收（{@code .workbuddy/probe-sse.ps1}）负责覆盖。
 * 把测不到的东西写成"已覆盖"，比不测更有害。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SseStallGuardTest {

    /** 与 seed 一致的已有课程，保证课程名等上下文可正常装配（不引入与本题无关的失败原因） */
    private static final long COURSE_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 假流式模型：接受请求后永不回调，等价于上游排队卡死 */
    @MockBean
    private StreamingChatLanguageModel streamingChatLanguageModel;

    private String studentToken;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode login = objectMapper.readTree(mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json;charset=UTF-8")
                        .content("{\"username\":\"student01\",\"password\":\"123456\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, login.path("code").asInt(), "测试前提：student01 登录成功");
        studentToken = login.path("data").path("token").asText();

        doAnswer(invocation -> {
            // 故意什么都不做：既不 onNext 也不 onComplete/onError
            return null;
        }).when(streamingChatLanguageModel).generate(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("上游永不回调时，必须在首字节预算内下发 event:error 而不是静默断流")
    void stalledModelYieldsActionableErrorEvent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/qa/chat/stream")
                        .header("Authorization", "Bearer " + studentToken)
                        .param("courseId", String.valueOf(COURSE_ID))
                        .param("question", "页面置换算法里为什么只有 FIFO 会出现 Belady 异常？")
                        .param("sessionId", "0"))
                .andReturn();

        String body = "";
        // 看门狗预算 2s（test profile），这里最多等 8s；等待本身就是断言：不能一直不出事件
        for (int i = 0; i < 80 && !body.contains("event:error"); i++) {
            TimeUnit.MILLISECONDS.sleep(100);
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        }

        assertTrue(body.contains("event:error"),
                "卡死场景必须给出 error 事件（旧实现只会静默断流，这里会失败）；实际报文：" + body);
        // 出处包在模型调用前就已下发，说明链路正常走到了模型这一步，失败点确实在上游
        assertTrue(body.contains("event:references"),
                "references 应先于 error 出现（契约：先出处后正文）；实际报文：" + body);
        assertTrue(body.contains("模型响应超时"),
                "文案必须说明发生了什么并给出可行动出口（#83 原则），实际报文：" + body);

        // 只取 error 事件后的 data 载荷做结构化断言，避免命中 references 里的原文
        String errorData = body.substring(body.indexOf("event:error"));
        JsonNode payload = objectMapper.readTree(
                errorData.substring(errorData.indexOf("data:") + 5).trim().lines().findFirst().orElse("{}"));
        assertEquals(5001, payload.path("errorCode").asInt(),
                "超时应归入既有 5001（模型异常）口径，不新造前端不认识的码：" + payload);
    }

    @Test
    @DisplayName("卡死轮次不得产出 done，也不得把空回答当成功落库")
    void stalledRoundDoesNotFakeSuccess() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/qa/chat/stream")
                        .header("Authorization", "Bearer " + studentToken)
                        .param("courseId", String.valueOf(COURSE_ID))
                        .param("question", "工作集模型和抖动是什么关系？")
                        .param("sessionId", "0"))
                .andReturn();

        String body = "";
        for (int i = 0; i < 80 && !body.contains("event:error"); i++) {
            TimeUnit.MILLISECONDS.sleep(100);
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        }

        // 关键反向断言：done 包意味着"正常答完"，卡死场景绝不能伪装成功
        //（#83 已经处理过一次同类问题：硬编码 finishReason=stop 把"一个字没推"伪装成答完）
        assertTrue(!body.contains("event:done"),
                "超时轮次不得下发 done，否则前端会按成功处理并把空正文写进历史：" + body);
    }
}
