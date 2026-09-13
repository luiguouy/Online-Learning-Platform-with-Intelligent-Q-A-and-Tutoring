package com.smartqa.platform.service.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartqa.platform.entity.QaRecord;
import com.smartqa.platform.entity.QaSession;
import com.smartqa.platform.service.QaRecordService;
import com.smartqa.platform.service.QaSessionService;
import dev.langchain4j.data.message.AiMessage;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SSE 流式问答「接线」集成测试（#34 验收：主流程 + 边界，不依赖真实大模型 Key）
 *
 * 测试替身策略：
 *   - StreamingChatLanguageModel 用 @MockBean 替换为假流式模型（同步回调，无外网调用）；
 *   - 向量存储走 test profile 的 DISABLED -> InMemoryEmbeddingStore（零外部依赖）；
 *   - MySQL 使用本地真实 smart_qa 库（需先导入 schema.sql + data.sql）；
 *   - 登录走真实 POST /api/auth/login（student02/123456，seed 中无历史问答记录，
 *     便于「不落库」负断言）；测试课件挂独立 courseId=888888，
 *     @AfterEach 按该 courseId 清理，不污染演示数据（seed 的 session 1 / record 1 不动）。
 *
 * 覆盖场景（DoD 第 2 层「3 个边界」+ 契约核对）：
 *   1. 首问懒建会话：references 首包 + {"delta"} 帧 + done 含 recordId，qa_record 真落库、出处快照正确
 *   2. 超长提问：正常出流，question/answer 完整落库不截断
 *   3. 续问复用会话：不再新建 qa_session
 *   4. 越权访问他人会话（seed session 1 属 student01）：error {"errorCode":403}，不落库
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SseWiringIntegrationTest {

    /** 独立测试课程 ID，与 seed 数据（course 1~3）完全隔离，课后清理 */
    private static final long COURSE_ID = 888888L;

    /** OS 课件内容，与 RagIsolationTest 同源片段，保证 BGE 检索可命中 */
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

    @BeforeEach
    void setUp() throws Exception {
        // 课件入库独立测试课程（InMemory 向量库为进程内单例，跨用例先删旧块防止重复召回）
        ingestionService.removeDocumentVectors(101L);
        int chunks = ingestionService.ingestText(OS_DOC, "第3章_内存管理.md", COURSE_ID, 101L);
        assertTrue(chunks >= 1, "测试前提：课件应至少切出 1 块");

        // 真实登录接口取 token，鉴权链路与生产一致（Bearer 前缀 + Sa-Token 拦截器）
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student02\",\"password\":\"123456\"}"))
                .andReturn();
        JsonNode loginBody = objectMapper.readTree(
                login.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(200, loginBody.path("code").asInt(), "测试前提：student02 登录成功");
        studentToken = loginBody.path("data").path("token").asText();
        assertTrue(!studentToken.isBlank(), "测试前提：登录返回 token");

        // 假流式模型：同步吐两个 token 后 onComplete（携带 TokenUsage，供 totalTokens 断言）
        doAnswer(invocation -> {
            StreamingResponseHandler<AiMessage> handler = invocation.getArgument(1);
            handler.onNext("在操作系统中，");
            handler.onNext("虚拟内存将物理内存分页管理。");
            handler.onComplete(Response.from(AiMessage.from("完整回答"), new TokenUsage(120, 30, 150)));
            return null;
        }).when(streamingChatLanguageModel).generate(anyList(),
                org.mockito.ArgumentMatchers.any());
    }

    @AfterEach
    void cleanUp() {
        // 只清本测试课程（COURSE_ID）产生的数据，seed 演示数据不动
        qaRecordService.remove(Wrappers.<QaRecord>lambdaQuery()
                .eq(QaRecord::getCourseId, COURSE_ID));
        qaSessionService.remove(Wrappers.<QaSession>lambdaQuery()
                .eq(QaSession::getCourseId, COURSE_ID));
    }

    @Test
    @DisplayName("主流程：首问(sessionId=0)懒建会话，事件契约与 qa_record 落库全符合 4.2")
    void firstQuestionLazySession() throws Exception {
        String question = "什么是虚拟内存？";
        String body = stream(question, 0L);

        List<String> events = sseEventNames(body);
        assertEquals(List.of("references", "message", "message", "done"), events,
                "事件序列应为 首包references → delta×N → done，实际报文：" + body);

        JsonNode done = jsonOfEvent(body, "done");
        long recordId = done.path("recordId").asLong();
        long sessionId = done.path("sessionId").asLong();
        assertTrue(recordId > 0, "done 包必须携带正 recordId，实际：" + done);
        assertEquals("stop", done.path("finishReason").asText());
        assertEquals(150, done.path("totalTokens").asInt(), "totalTokens 应透传模型 TokenUsage");

        // —— 落库断言（查真表，不是内存）——
        QaRecord record = qaRecordService.getById(recordId);
        assertNotNull(record, "recordId 应能查到 qa_record 行");
        assertEquals(question, record.getQuestion());
        assertEquals("在操作系统中，虚拟内存将物理内存分页管理。", record.getAnswer(),
                "流式 token 必须完整攒齐后落库");
        assertNotNull(record.getGroundingReferences());
        assertEquals(1, record.getGroundingReferences().size());
        assertEquals("第3章_内存管理.md", record.getGroundingReferences().get(0).getFileName());
        assertEquals(COURSE_ID, record.getCourseId());
        assertEquals(sessionId, record.getSessionId());

        // —— references 首包：字段与 SseReferenceVO 契约逐一对应 ——
        JsonNode refs = jsonOfEvent(body, "references");
        assertTrue(refs.isArray() && refs.size() == 1, "references 应为数组且命中 1 块");
        JsonNode ref = refs.get(0);
        assertEquals(101L, ref.path("docId").asLong());
        assertEquals("第3章_内存管理.md", ref.path("fileName").asText());
        assertTrue(ref.hasNonNull("chunkIndex"));
        double score = ref.path("score").asDouble();
        assertTrue(score >= 0.70 && score <= 1.0, "score 应过阈值，实际 " + score);
        assertTrue(ref.path("snippet").asText().contains("虚拟内存"));

        // —— message 帧是 JSON delta，不是裸文本 ——
        assertTrue(body.contains("{\"delta\":"), "message 帧必须为 {\"delta\":...} JSON");

        // —— 懒建会话：标题取提问前 15 字 ——
        QaSession session = qaSessionService.getById(sessionId);
        assertNotNull(session);
        assertEquals(question, session.getSessionTitle());
    }

    @Test
    @DisplayName("边界1：超长提问（10000 字）正常出流，question 完整落库")
    void oversizedQuestion() throws Exception {
        String question = "虚拟内存".repeat(2500);
        String body = stream(question, 0L);

        JsonNode done = jsonOfEvent(body, "done");
        QaRecord record = qaRecordService.getById(done.path("recordId").asLong());
        assertNotNull(record);
        assertEquals(question, record.getQuestion(), "超长提问不得截断");
        assertTrue(sseEventNames(body).contains("done"));
    }

    @Test
    @DisplayName("边界2：续问复用已有会话，不再新建 qa_session")
    void continuationReusesSession() throws Exception {
        long ownedSessionId = qaSessionService.createSessionLazyForUser(3L, COURSE_ID, "前一条提问");

        String body = stream("那页面置换算法呢？", ownedSessionId);
        JsonNode done = jsonOfEvent(body, "done");
        assertEquals(ownedSessionId, done.path("sessionId").asLong(), "done 包 sessionId 应回显原会话");

        QaRecord last = qaRecordService.getOne(Wrappers.<QaRecord>lambdaQuery()
                .eq(QaRecord::getSessionId, ownedSessionId)
                .orderByDesc(QaRecord::getId)
                .last("LIMIT 1"));
        assertNotNull(last, "续问记录应挂到原会话");
        long sessionCount = qaSessionService.count(Wrappers.<QaSession>lambdaQuery()
                .eq(QaSession::getCourseId, COURSE_ID));
        assertEquals(1, sessionCount, "续问不得新建会话");
    }

    @Test
    @DisplayName("边界3：访问他人会话（seed session 1 属 student01）→ error 403，不落库")
    void foreignSessionRejected() throws Exception {
        // seed：qa_session id=1 属 user_id=2（student01）；本测试以 student02（id=3）登录
        String body = stream("偷看别人的提问", 1L);

        JsonNode error = jsonOfEvent(body, "error");
        assertEquals(403, error.path("errorCode").asInt());
        assertTrue(error.path("message").asText().contains("无权"));
        assertEquals(List.of("error"), sseEventNames(body), "越权只应收到 error，不得进入推流");

        assertEquals(0, qaRecordService.count(Wrappers.<QaRecord>lambdaQuery()
                .eq(QaRecord::getUserId, 3L)), "越权请求不得产生任何问答记录");
    }

    // ==================== 辅助 ====================

    /**
     * 发起 SSE 请求：先轮询等待 sseExecutor 内流完成（@Async + SseEmitter 在 MockMvc
     * 下不触发 asyncStarted，不能用 asyncDispatch），再取响应体。
     */
    private String stream(String question, long sessionId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/qa/chat/stream")
                        .header("Authorization", "Bearer " + studentToken)
                        .param("courseId", String.valueOf(COURSE_ID))
                        .param("question", question)
                        .param("sessionId", String.valueOf(sessionId)))
                .andReturn();

        // 异步流在 sseExecutor 线程执行，轮询响应体出现终止事件（done 或 error）
        String body = "";
        for (int i = 0; i < 100 && !streamFinished(body); i++) {
            TimeUnit.MILLISECONDS.sleep(100);
            body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        }
        if (!streamFinished(body)) {
            // 超时兜底：走一次 asyncDispatch 暴露真实状态，方便定位
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
