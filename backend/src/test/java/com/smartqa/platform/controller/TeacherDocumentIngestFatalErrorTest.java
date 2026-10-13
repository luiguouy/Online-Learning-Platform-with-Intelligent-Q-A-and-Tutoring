package com.smartqa.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartqa.platform.entity.CourseDocument;
import com.smartqa.platform.service.CourseDocumentService;
import com.smartqa.platform.service.rag.DocumentIngestionService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Issue #74 §三（B 侧另一半）：切块向量化阶段抛出的 {@code Error} 必须让课件落 {@code FAILED}，
 * 不得让状态机停在 {@code PARSING}。
 *
 * <p><b>被钉住的缺陷</b>（修复前）：{@code submitIngestion} 的异步块只
 * {@code catch (Exception e)}。而 {@code ingest()} 仅在 Tika 解析段借
 * {@code Future.get()} 把 {@code Error} 转成 {@code Exception}；之后的
 * {@code embedAll} / {@code addAll}（{@code splitEmbedAndStore}）没有任何包装，
 * {@code UnsatisfiedLinkError}（BGE/ONNX 原生库缺失）、{@code NoClassDefFoundError}
 * （依赖缺类）这类 {@code Error} 会直接冒泡、且 {@code catch (Exception)} 接不到。
 * 又因为该任务是 {@code CompletableFuture.runAsync(...)} 提交、<b>返回值被丢弃、无人
 * {@code join()}/{@code get()}</b>，{@code CompletableFuture$AsyncSupply.run()} 自身
 * {@code catch (Throwable)} 后只把异常存进结果对象 —— 既不打印也不传播，是<b>彻底静默</b>的。
 * 结果是课件永久停在 {@code PARSING}、{@code error_msg} 为空，连一条 ERROR 日志都没有；
 * 而 reindex 的状态 CAS 只放行 {@code CHUNKED/FAILED}，该课件从此<b>无法重建</b>，只能重传。
 * </p>
 *
 * <p><b>为什么用 {@code @SpyBean} 而不是 {@code @MockBean}</b>：只把"向量化这一步"打桩成抛
 * {@code Error}，其余（切割、状态回写、真实 MySQL 落库）必须走真实现，否则
 * "状态落到 FAILED"这条断言测不到任何东西，沿用上述两个测试类的同一手法：
 * {@code TeacherDocumentReindexGuardTest} 与 {@code TeacherDocumentDeleteRaceTest}。</p>
 *
 * <p><b>为什么必须验证"修复前是红的"</b>：本类第一条用例在修复前应因
 * {@code awaitParseSettled} 超时而失败（状态停在 PARSING）。若修复前它就绿，
 * 说明断言没有覆盖到该路径 —— 即 A 在 #74 里对 #82 分支提的那种"给实现钉正当性"的假测试。</p>
 *
 * <p>走 {@code test} profile（Chroma {@code DISABLED} → InMemory），需要真实 MySQL。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherDocumentIngestFatalErrorTest {

    /** teacher01（seed id=1）任教的课程 */
    private static final long COURSE_ID = 1L;

    private static final float[] FIXED_VECTOR = { 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f };

    /** 模拟"依赖缺类"（属 LinkageError 一族），与 #65 spike 里真实撞到的形态一致 */
    private static final String SIMULATED_FATAL_CLASS = "com/smartqa/platform/rag/OnnxRuntimeMissing";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private CourseDocumentService docService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 固定维度向量，省掉真实 BGE 模型加载；本类只关心状态回写，不关心向量取值 */
    @MockBean
    private dev.langchain4j.model.embedding.EmbeddingModel embeddingModel;

    /** 真实切块实现，只在"向量化"这一刻打桩 */
    @SpyBean
    private DocumentIngestionService ingestionService;

    private String teacherToken;
    private final List<Long> createdDocIds = new ArrayList<>();
    private final List<String> uploadedFilePaths = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        reset(ingestionService);
        createdDocIds.clear();
        uploadedFilePaths.clear();

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(FIXED_VECTOR))
                    .toList());
        });

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"teacher01\",\"password\":\"123456\"}"))
                .andReturn();
        JsonNode body = bodyOf(login);
        assertEquals(200, body.path("code").asInt(), "测试前提：teacher01 真实登录成功");
        teacherToken = body.path("data").path("token").asText();
    }

    @AfterEach
    void cleanUp() {
        // 必须 reset：@SpyBean 是 Spring 单例，上下文缓存后会被后续测试类共用，
        // 不打桩残留会让其它测试类假红/假绿。
        reset(ingestionService);
        for (Long docId : createdDocIds) {
            embeddingStore.removeAll(new IsEqualTo("docId", String.valueOf(docId)));
        }
        for (Long docId : createdDocIds) {
            jdbcTemplate.update("DELETE FROM course_document WHERE id = ?", docId);
        }
        for (String path : uploadedFilePaths) {
            File f = new File(path);
            if (f.exists() && !f.delete()) {
                throw new IllegalStateException("清理失败：无法删除测试上传文件 " + path);
            }
        }
    }

    @Test
    @DisplayName("#74 §三 向量化抛 NoClassDefFoundError：状态须落 FAILED（不得卡 PARSING），且文案不得泄漏技术串")
    void fatalErrorDuringVectorizationMarksFailedInsteadOfStuckParsing() throws Exception {
        // 打桩：整条 ingest 直接抛 Error（等价于 embedAll/addAll 阶段依赖缺类）
        doThrow(new NoClassDefFoundError(SIMULATED_FATAL_CLASS))
                .when(ingestionService).ingest(any(), any(), any(), any(), any());

        long docId = upload("#74_致命错误.md");

        // 修复前：这里会等满 30s 后抛 AssertionError（状态永久停在 PARSING）
        awaitParseSettled(docId);

        CourseDocument doc = docService.getById(docId);
        assertNotNull(doc, "课件记录应存在");
        assertEquals(CourseDocument.STATUS_FAILED, doc.getParseStatus(),
                "向量化阶段的 Error 必须被兜住并回写终态 FAILED；"
                        + "停在 PARSING 的课件无法自愈（无任何超时兜底，reindex 的 CAS 也不放行），只能重传");
        assertNotNull(doc.getErrorMsg(), "FAILED 必须带可读 errorMsg，供教师端 tooltip");
        assertFalse(doc.getErrorMsg().isBlank(), "errorMsg 不得为空串");
        assertTrue(doc.getErrorMsg().contains("系统组件异常"),
                "框架级故障应给【系统组件异常】一档文案，实际：" + doc.getErrorMsg());
        assertFalse(doc.getErrorMsg().contains(SIMULATED_FATAL_CLASS),
                "不得把类名技术串当作 errorMsg 展示给教师，实际：" + doc.getErrorMsg());

        // 收场必须是"可重试的终态"：FAILED 在 CAS 放行集合内，撤掉故障后应能重建
        reset(ingestionService);
        assertEquals(200, bodyOf(reindex(docId)).path("code").asInt(),
                "FAILED 属 CAS 放行集合，应可重建；拿到 409 说明状态仍卡在 PARSING");
        awaitParseSettled(docId);
        assertEquals(CourseDocument.STATUS_CHUNKED, status(docId), "撤掉故障后重建应正常完成");
    }

    @Test
    @DisplayName("#74 §三 回归保护：普通 Exception 仍沿用 ingest 的面向用户文案（不得被 Error 分流改坏）")
    void ordinaryFailureKeepsUserFacingMessage() throws Exception {
        String userFacing = "课件文本内容超过解析上限（200000 字符），请拆分后重新上传: #74_超限.md";
        doThrow(new RuntimeException(userFacing))
                .when(ingestionService).ingest(any(), any(), any(), any(), any());

        long docId = upload("#74_超限.md");
        awaitParseSettled(docId);

        CourseDocument doc = docService.getById(docId);
        assertEquals(CourseDocument.STATUS_FAILED, doc.getParseStatus(), "普通失败同样应落 FAILED");
        assertEquals(userFacing, doc.getErrorMsg(),
                "Exception 分支必须原样沿用 ingest 的面向用户文案（#55 口径），不得被本次改动改写");
    }

    // ───────────────────────────── helper ─────────────────────────────

    private String status(long docId) {
        CourseDocument doc = docService.getById(docId);
        assertNotNull(doc, "课件记录应存在");
        return doc.getParseStatus();
    }

    private MvcResult reindex(long docId) throws Exception {
        return mockMvc.perform(post("/api/teacher/docs/{id}/reindex", docId)
                .header("Authorization", "Bearer " + teacherToken))
                .andReturn();
    }

    /** 走真实上传接口，返回 docId；顺带登记落盘路径供清理 */
    private long upload(String fileName) throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", fileName, "text/markdown",
                ("# " + fileName + "\n虚拟内存与页面置换算法：FIFO、LRU、OPT。").getBytes(StandardCharsets.UTF_8));

        JsonNode body = bodyOf(mockMvc.perform(multipart("/api/teacher/docs/upload")
                .file(file)
                .param("courseId", String.valueOf(COURSE_ID))
                .header("Authorization", "Bearer " + teacherToken))
                .andReturn());

        assertEquals(200, body.path("code").asInt(), "上传应成功，实际响应：" + body);
        long docId = body.path("data").asLong();
        assertTrue(docId > 0, "上传应返回正 docId，实际：" + body);

        createdDocIds.add(docId);
        CourseDocument doc = docService.getById(docId);
        assertNotNull(doc, "上传后应能查到课件记录");
        uploadedFilePaths.add(doc.getFilePath());
        return docId;
    }

    /**
     * 轮询业务终态（CHUNKED / FAILED），超时即 fail —— 沿用 #58 审查 P2-3 确立的口径：
     * 不去采样线程池空闲状态：{@code addWorker} 把首个任务直接交给 Worker、从不入队，
     * 会让"队列空且 activeCount==0"瞬时同时成立，是真实的抖动源。
     */
    private void awaitParseSettled(long docId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            CourseDocument doc = docService.getById(docId);
            if (doc != null
                    && (CourseDocument.STATUS_CHUNKED.equals(doc.getParseStatus())
                            || CourseDocument.STATUS_FAILED.equals(doc.getParseStatus()))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("等待课件进入终态（CHUNKED/FAILED）超时, docId=" + docId
                + ", 当前状态=" + status(docId));
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
