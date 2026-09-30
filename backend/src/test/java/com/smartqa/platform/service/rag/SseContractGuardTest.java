package com.smartqa.platform.service.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartqa.platform.entity.QaRecord;
import com.smartqa.platform.entity.QaSession;
import com.smartqa.platform.service.QaRecordService;
import com.smartqa.platform.service.QaSessionService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SSE 契约守护测试（Issue #57 第二、三项的服务端验收）
 *
 * <p>
 * 与其余测试类的职责分工（不重叠）：
 * {@code SseWiringIntegrationTest} 验 SSE 端到端接线，{@code SseProtocolBoundaryTest}
 * 验真实
 * 协议栈下的提问长度边界，{@code RagIsolationTest} 验检索侧课程隔离。
 * 本类只钉两件事：<b>会话与课程的绑定一致性</b>、<b>RAG 提示词的防幻觉契约</b>。
 * </p>
 *
 * <p>
 * 覆盖场景：
 * <ol>
 * <li>#57 二：跨课程复用 sessionId → error 400、不落库、不新建会话（根治跳课混写）</li>
 * <li>#57 二 不回归：同课程复用 sessionId 照常出流，仍不新建会话</li>
 * <li>#57 二 校验顺序：他人会话即使课程也不匹配，仍必须先报 403 而非 400（防信息泄漏）</li>
 * <li>#57 三：检索命中时，发给模型的提示词含「未找到须首句明确说明」契约约束</li>
 * <li>#57 三：空检索分支的注入文本同样含该约束（演示 Q5 防幻觉拒答最依赖此分支）</li>
 * </ol>
 *
 * <p>
 * 测试替身策略沿用 {@code SseWiringIntegrationTest}：假流式模型（@MockBean，同步回调、无外网）、
 * 向量库走 test profile 的 DISABLED → InMemoryEmbeddingStore、MySQL 用本地真实 smart_qa 库。
 * 课程 ID 取 570001/570002，与 seed（1~3）及其它测试类的 888888 完全隔离，@AfterEach 清理。
 * </p>
 *
 * <p>
 * 提示词断言方式：不用反射读私有常量，而是在假模型里<b>捕获真正传给
 * {@code generate()} 的 UserMessage 文本</b>——这样断言的是模型实际收到的内容，
 * 一旦有人改了拼装逻辑（而不仅是常量），用例照样能发现。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SseContractGuardTest {

    /** 有课件的课程：用于「检索命中」与「同课程复用会话」场景 */
    private static final long COURSE_A = 570001L;

    /** 无课件的课程：向量库里没有任何该 courseId 的切片，检索必然落空（确定性构造空检索） */
    private static final long COURSE_B = 570002L;

    /** 本类专属 docId，与其它测试类的 101 隔离，避免进程内向量库互相污染 */
    private static final long DOC_ID = 5701L;

    /** student02 的 user_id（seed 数据），该账号无历史问答记录，便于「不落库」负断言 */
    private static final long STUDENT02_ID = 3L;

    /** 契约要求的未找到声明原文（DEV_SPECIFICATION §5.2 第 2 条） */
    private static final String NOT_FOUND_PHRASE = "在当前课程课件中未找到该问题的明确说明";

    private static final String OS_DOC = """
            虚拟内存是计算机系统内存管理的一种技术。它使得应用程序认为它拥有连续的可用内存，\
            而实际上，它通常是被分隔成多个物理内存碎片，还有部分暂时存储在外部磁盘存储器上，\
            在需要时进行数据交换。页面置换算法包括FIFO、LRU和OPT等。""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private QaSessionService qaSessionService;

    @Autowired
    private QaRecordService qaRecordService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StreamingChatLanguageModel streamingChatLanguageModel;

    private String studentToken;

    /** 捕获真正发给大模型的提示词文本，供 #57 三 的契约断言使用 */
    private final AtomicReference<String> capturedPrompt = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws Exception {
        // 只给 COURSE_A 入课件；COURSE_B 刻意留空以确定性触发空检索分支
        ingestionService.removeDocumentVectors(DOC_ID);
        int chunks = ingestionService.ingestText(OS_DOC, "第3章_内存管理.md", COURSE_A, DOC_ID);
        assertTrue(chunks >= 1, "测试前提：COURSE_A 课件应至少切出 1 块");
        capturedPrompt.set("");

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"student02\",\"password\":\"123456\"}"))
                .andReturn();
        JsonNode loginBody = objectMapper.readTree(
                login.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, loginBody.path("code").asInt(), "测试前提：student02 登录成功");
        studentToken = loginBody.path("data").path("token").asText();
        assertTrue(!studentToken.isBlank(), "测试前提：登录返回 token");

        // 假流式模型：先把收到的提示词存下来，再同步吐一个 token 并 onComplete
        doAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            ChatMessage first = messages.get(0);
            assertTrue(first instanceof UserMessage, "SSE 链路应只发一条 UserMessage");
            capturedPrompt.set(((UserMessage) first).singleText());

            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("虚拟内存将物理内存分页管理。");
            handler.onComplete(Response.from(AiMessage.from("完整回答"), new TokenUsage(100, 20, 120)));
            return null;
        }).when(streamingChatLanguageModel).generate(anyList(),
                org.mockito.ArgumentMatchers.any());
    }

    @AfterEach
    void cleanUp() {
        ingestionService.removeDocumentVectors(DOC_ID);
        for (long courseId : new long[] { COURSE_A, COURSE_B }) {
            qaRecordService.remove(Wrappers.<QaRecord>lambdaQuery()
                    .eq(QaRecord::getCourseId, courseId));
            qaSessionService.remove(Wrappers.<QaSession>lambdaQuery()
                    .eq(QaSession::getCourseId, courseId));
        }
    }

    // ==================== #57 二：会话与课程的绑定一致性 ====================

    @Test
    @DisplayName("#57二：跨课程复用 sessionId → error 400，不落库、不新建会话")
    void crossCourseSessionRejected() throws Exception {
        // 构造前端「切课后仍带旧 sessionId」的真实后果：会话属 COURSE_A，请求打 COURSE_B
        long sessionOfCourseA = qaSessionService.createSessionLazyForUser(
                STUDENT02_ID, COURSE_A, "前一条提问");

        String body = stream(COURSE_B, "那页面置换算法呢？", sessionOfCourseA);

        JsonNode error = jsonOfEvent(body, "error");
        assertEquals(400, error.path("errorCode").asInt(),
                "跨课程复用会话应为业务参数错误 400，实际报文：" + body);
        assertTrue(error.path("message").asText().contains("不属于当前课程"),
                "错误消息应说明会话与课程不匹配，实际：" + error.path("message").asText());
        assertEquals(List.of("error"), sseEventNames(body),
                "被拒的请求只应收到 error，不得进入推流，实际报文：" + body);

        // 不得落库：若未拦住，会写出 course_id=COURSE_B 而 session_id 属 COURSE_A 的矛盾记录
        assertEquals(0, qaRecordService.count(Wrappers.<QaRecord>lambdaQuery()
                .eq(QaRecord::getCourseId, COURSE_B)),
                "跨课程请求不得产生任何问答记录");
        assertEquals(0, qaRecordService.count(Wrappers.<QaRecord>lambdaQuery()
                .eq(QaRecord::getSessionId, sessionOfCourseA)),
                "跨课程请求也不得往原会话里混写记录");
        // 不得静默新建会话（自愈式处理会掩盖前端守卫缺失 #59）
        assertEquals(0, qaSessionService.count(Wrappers.<QaSession>lambdaQuery()
                .eq(QaSession::getCourseId, COURSE_B)),
                "被拒的请求不得在目标课程下新建会话");
    }

    @Test
    @DisplayName("#57二 不回归：同课程复用 sessionId 照常出流，仍不新建会话")
    void sameCourseSessionStillReused() throws Exception {
        long ownedSessionId = qaSessionService.createSessionLazyForUser(
                STUDENT02_ID, COURSE_A, "前一条提问");

        String body = stream(COURSE_A, "什么是虚拟内存？", ownedSessionId);

        JsonNode done = jsonOfEvent(body, "done");
        assertEquals(ownedSessionId, done.path("sessionId").asLong(),
                "done 包 sessionId 应回显原会话");
        assertEquals(1, qaSessionService.count(Wrappers.<QaSession>lambdaQuery()
                .eq(QaSession::getCourseId, COURSE_A)),
                "同课程续问不得新建会话");
        QaRecord record = qaRecordService.getById(done.path("recordId").asLong());
        assertNotNull(record, "同课程续问应正常落库");
        assertEquals(ownedSessionId, record.getSessionId());
        assertEquals(COURSE_A, record.getCourseId(),
                "记录的 course_id 与所属会话的 course_id 必须一致（这正是 #57 二 要防的矛盾）");
    }

    @Test
    @DisplayName("#57二 校验顺序：他人会话即使课程也不匹配，仍先报 403 而非 400")
    void ownershipCheckPrecedesCourseCheck() throws Exception {
        // seed：qa_session id=1 属 student01(user_id=2)；本测试以 student02(id=3) 登录，
        // 且故意传一个必然不匹配的 courseId —— 两项校验都会失败，必须是归属校验先赢。
        String body = stream(COURSE_B, "偷看别人的提问", 1L);

        JsonNode error = jsonOfEvent(body, "error");
        assertEquals(403, error.path("errorCode").asInt(),
                "归属校验必须先于课程校验，否则会向越权者泄漏会话所属课程，实际报文：" + body);
        assertTrue(error.path("message").asText().contains("无权"),
                "错误消息应为越权语义，实际：" + error.path("message").asText());
    }

    // ==================== #57 三：防幻觉提示词契约 ====================

    @Test
    @DisplayName("#57三：检索命中时，发给模型的提示词含「未找到须首句明确说明」契约约束")
    void promptCarriesNotFoundContractOnHit() throws Exception {
        String body = stream(COURSE_A, "什么是虚拟内存？", 0L);
        assertTrue(sseEventNames(body).contains("done"), "测试前提：命中场景应正常出流，报文：" + body);

        String prompt = capturedPrompt.get();
        assertTrue(!prompt.isBlank(), "测试前提：应已捕获到发给模型的提示词");
        // 检索确实命中（context 非空），因此不应出现空检索分支的措辞
        assertTrue(prompt.contains("虚拟内存"), "提示词应带上检索到的课件内容");
        assertTrue(!prompt.contains("本次检索未命中任何课件片段"),
                "命中场景不应走空检索分支的注入文本");

        assertNotFoundContract(prompt);
    }

    @Test
    @DisplayName("#57三：空检索分支的注入文本同样含该约束（演示 Q5 防幻觉拒答依赖此分支）")
    void promptCarriesNotFoundContractOnEmptyRetrieval() throws Exception {
        // COURSE_B 向量库里一个切片都没有 → 检索必然落空 → 确定性走空检索分支
        String body = stream(COURSE_B, "请介绍一下量子计算的退相干机制", 0L);
        assertTrue(sseEventNames(body).contains("done"), "测试前提：空检索仍应正常出流，报文：" + body);

        String prompt = capturedPrompt.get();
        assertTrue(prompt.contains("本次检索未命中任何课件片段"),
                "应走空检索分支的注入文本，实际提示词：" + prompt);
        // 空检索分支不得只引导「基于学科常识作答」而丢掉未找到声明
        assertTrue(prompt.contains("【课外补充说明】"),
                "空检索分支仍须要求标注课外补充，实际提示词：" + prompt);

        assertNotFoundContract(prompt);
    }

    /**
     * 契约断言（DEV_SPECIFICATION §5.2 第 2 条 + MEMBER_A_DEV_GUIDE §4.2 第 3 条）：
     * 提示词必须同时具备「未提及时的判定条件」「首句明确声明未找到」「声明原文」三要素。
     */
    private static void assertNotFoundContract(String prompt) {
        assertTrue(prompt.contains("未提及该问题的相关信息"),
                "提示词应含「课件未提及」的判定条件，实际：" + prompt);
        assertTrue(prompt.contains("必须首句明确回答"),
                "提示词应要求把未找到声明放在首句（MEMBER_A_DEV_GUIDE §4.2 第 3 条），实际：" + prompt);
        assertTrue(prompt.contains(NOT_FOUND_PHRASE),
                "提示词应含契约规定的未找到声明原文（DEV_SPECIFICATION §5.2 第 2 条），实际：" + prompt);
    }

    // ==================== 辅助 ====================

    /**
     * 发起 SSE 请求：@Async + SseEmitter 在 MockMvc 下不触发 asyncStarted，
     * 故轮询响应体直到出现终止事件（done / error），与 SseWiringIntegrationTest 同手法。
     */
    private String stream(long courseId, String question, long sessionId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/qa/chat/stream")
                .header("Authorization", "Bearer " + studentToken)
                .param("courseId", String.valueOf(courseId))
                .param("question", question)
                .param("sessionId", String.valueOf(sessionId)))
                .andReturn();

        String body = "";
        for (int i = 0; i < 100 && !streamFinished(body); i++) {
            TimeUnit.MILLISECONDS.sleep(100);
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        }
        if (!streamFinished(body)) {
            mockMvc.perform(asyncDispatch(result));
            throw new AssertionError("10s 内 SSE 流未完成，最后报文：" + body);
        }
        return body;
    }

    private static boolean streamFinished(String body) {
        return body.contains("event:done") || body.contains("event: done")
                || body.contains("event:error") || body.contains("event: error");
    }

    /** 按出现顺序提取 SSE 事件名 */
    private static List<String> sseEventNames(String body) {
        return body.lines()
                .filter(line -> line.startsWith("event:"))
                .map(line -> line.substring("event:".length()).trim())
                .toList();
    }

    /** 提取指定事件的 data 行并解析为 JSON（只认第一个匹配事件） */
    private JsonNode jsonOfEvent(String body, String eventName) throws Exception {
        String[] lines = body.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("event:" + eventName)
                    && i + 1 < lines.length && lines[i + 1].startsWith("data:")) {
                return objectMapper.readTree(lines[i + 1].substring("data:".length()).trim());
            }
        }
        throw new AssertionError("未找到事件 " + eventName + "，完整报文：" + body);
    }
}
