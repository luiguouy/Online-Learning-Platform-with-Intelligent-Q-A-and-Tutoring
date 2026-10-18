package com.smartqa.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartqa.platform.entity.CourseDocument;
import com.smartqa.platform.service.CourseDocumentService;
import com.smartqa.platform.service.rag.DocumentIngestionService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Issue #57 §一：reindex「清理旧向量失败」后不得把课件状态自锁于 {@code PARSING}（回归测试）。
 *
 * <p>
 * <b>被钉住的缺陷</b>（修复前）：{@code reindex} 的顺序是
 * ①{@code markParsingIfSettled} → ②{@code removeDocumentVectors} →
 * ③{@code submitIngestion}。
 * ① 已把状态提交为 {@code PARSING}；若 ② 抛异常，③ 不会执行、也没有任何地方回写状态，
 * 于是课件<b>永久停在 {@code PARSING}</b>。而 ① 的 CAS 只放行 {@code CHUNKED/FAILED}，
 * 所以再点一次「重建索引」会拿到 {@code 409}、刷新无效 —— 教师侧只剩"切块向量化中…"
 * 这一种显示（前端轮询 2 分钟后自行停止），看不出已经卡死。
 * </p>
 *
 * <p>
 * <b>本类的三条用例逐条对应 #57 §一的验收勾选项</b>：
 * </p>
 * <ol>
 * <li>清理失败后状态不停留 {@code PARSING}（应落 {@code FAILED}）→
 * {@link #cleanupFailureDoesNotLeaveStatusStuckInParsing()}</li>
 * <li>失败后可以再次重建（{@code FAILED} 在 CAS 放行集合内）→ 同上用例后半段</li>
 * <li>并发/邻接场景无「误删他人向量」→
 * {@link #cleanupFailureDoesNotTouchOtherDocumentsVectors()}</li>
 * </ol>
 *
 * <p>
 * <b>为什么用 {@code @SpyBean} 而不是 {@code @MockBean} DocumentIngestionService</b>：
 * 本类要证明的是"清理这一步失败时的状态收场"，其余路径必须走真实现，
 * 否则"重建成功"那条断言就测不到任何东西（与 {@code TeacherDocumentDeleteRaceTest} 同一手法）。
 * </p>
 *
 * <p>
 * 用 {@code @MockBean EmbeddingModel} 固定 8 维向量，不加载 45MB BGE；
 * 走 {@code test} profile（Chroma {@code DISABLED} → InMemory），需要真实 MySQL。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherDocumentReindexGuardTest {

    /** teacher01（seed id=1）任教的课程 */
    private static final long COURSE_ID = 1L;

    private static final float[] FIXED_VECTOR = { 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f };
    private static final Embedding QUERY_EMBEDDING = Embedding.from(FIXED_VECTOR);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private CourseDocumentService docService;

    /** 仅用于清理本测试写入的数据，避免污染演示库 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 固定维度向量，省掉真实模型加载；reindex 的成功路径需要它正常返回 */
    @MockBean
    private dev.langchain4j.model.embedding.EmbeddingModel embeddingModel;

    /** 真实切块/清理实现，只在"清理失败"这一刻打桩 */
    @SpyBean
    private DocumentIngestionService ingestionService;

    private String teacherToken;
    private final List<Long> createdDocIds = new ArrayList<>();
    private final List<String> uploadedFilePaths = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
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
    @DisplayName("#57 §一 清理旧向量失败：状态不得停留 PARSING，须落 FAILED 且可再次重建")
    void cleanupFailureDoesNotLeaveStatusStuckInParsing() throws Exception {
        long docId = upload("重建失败收场.md");
        awaitParseSettled(docId);
        assertEquals(CourseDocument.STATUS_CHUNKED, status(docId), "前置：重建对象应已是 CHUNKED");

        // 只对这一个 docId 的清理打桩，其它 docId 仍走真实现
        doThrow(new RuntimeException("simulated vector store outage"))
                .when(ingestionService).removeDocumentVectors(docId);

        JsonNode body = bodyOf(reindex(docId));

        // 契约：统一 Result 包装，业务码 500（HTTP 仍 200）
        assertEquals(500, body.path("code").asInt(),
                "清理失败应返回可读的业务码而非静默成功，实际：" + body);

        // 验收一：状态不得停在 PARSING
        String afterFailure = status(docId);
        assertEquals(CourseDocument.STATUS_FAILED, afterFailure,
                "清理失败后状态必须落终态 FAILED；若为 PARSING 则该课件已自锁（#57 §一 复现）");
        assertNotNull(docService.getById(docId).getErrorMsg(),
                "FAILED 应带可读 errorMsg，供教师端 tooltip");

        // 验收二：撤掉故障后，同一个课件应能再次重建（FAILED 在 CAS 放行集合内）
        reset(ingestionService);
        JsonNode retry = bodyOf(reindex(docId));
        assertEquals(200, retry.path("code").asInt(),
                "失败后应可再次重建；拿到 409 说明状态仍卡在 PARSING，实际：" + retry);

        awaitParseSettled(docId);
        assertEquals(CourseDocument.STATUS_CHUNKED, status(docId),
                "重试后应正常完成重建（证明 CAS 未被破坏）");
    }

    @Test
    @DisplayName("#57 §一 清理失败不得波及其他课件的向量（removeDocumentVectors 严格按 docId 过滤）")
    void cleanupFailureDoesNotTouchOtherDocumentsVectors() throws Exception {
        long docA = upload("受影响课件.md");
        long docB = upload("无关课件.md");
        awaitParseSettled(docA);
        awaitParseSettled(docB);

        int bBefore = segmentsOf(docB);
        assertTrue(bBefore > 0, "前置：docB 应有切片，否则这条断言是空的");
        assertTrue(segmentsOf(docA) > 0, "前置：docA 应有切片");

        doThrow(new RuntimeException("simulated vector store outage"))
                .when(ingestionService).removeDocumentVectors(docA);

        assertEquals(500, bodyOf(reindex(docA)).path("code").asInt(), "docA 重建应因清理失败而报错");

        // 验收三：docA 的清理无论删了多少，都不得动到 docB 的切片
        assertEquals(bBefore, segmentsOf(docB),
                "docA 的清理失败不得误删 docB 的向量切片（IsEqualTo(docId) 过滤的钉桩）");
        assertEquals(CourseDocument.STATUS_CHUNKED, status(docB), "docB 状态也不受影响");
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

    /** 按 docId 统计向量库切片数（与向量取值无关，只做存在性计数） */
    private int segmentsOf(long docId) {
        return embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(QUERY_EMBEDDING)
                .filter(new IsEqualTo("docId", String.valueOf(docId)))
                .maxResults(100)
                .minScore(0.0)
                .build()).matches().size();
    }

    /**
     * 轮询业务终态（CHUNKED / FAILED），超时即 fail —— 沿用 #58 审查 P2-3 确立的口径：
     * 不去采样线程池空闲状态（{@code addWorker} 把首个任务直接交给 Worker、从不入队，
     * 会让"队列空且 activeCount==0"瞬时同时成立，是真实的抖动源）。
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
