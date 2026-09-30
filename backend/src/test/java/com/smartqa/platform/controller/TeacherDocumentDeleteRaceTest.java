package com.smartqa.platform.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartqa.platform.entity.CourseDocument;
import com.smartqa.platform.service.CourseDocumentService;
import com.smartqa.platform.service.rag.DocumentIngestionService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Issue #58 回归测试：解析中的课件被删除后，在途切块线程不得留下「幽灵参考资料」。
 *
 * <p><b>缺陷时序</b>（原始代码 {@code delete()} 先清向量、再删记录，且 {@code ingest} 是
 * 切完全部片段后<b>一次性</b> {@code embeddingStore.addAll(...)}）：</p>
 * <pre>
 *   切块线程:  ──解析──切块──────────────────────────▶ addAll ──▶ 状态回写
 *   删除请求:              ──清向量(空)──删记录────────┘
 *                                                    ↑ 向量在这里被"写回"，记录已消失
 * </pre>
 * <p>结果是学生在已删课件的课程里提问，仍能检索到该课件的内容（references 显示"已删除的课件名"）。</p>
 *
 * <p><b>本测试如何把"靠时序运气"变成"可控构造"</b>（Issue 复现步骤 1~3）：
 * {@code embedAll} 是 {@code ingest} 里"切完所有片段"与"一次性 addAll"之间唯一的可插入点，
 * 因此用 {@link MockBean} 替换 {@link EmbeddingModel}，在其中用 {@link CountDownLatch} 把切块线程
 * <b>精确暂停在 addAll 之前</b>；暂停期间走真实接口删除课件；再放行线程完成 addAll 与状态回写；
 * 最后按 {@code docId} 检索向量库，断言切片数为 0。</p>
 *
 * <p><b>为什么不用 {@code @MockBean DocumentIngestionService}</b>：那样 {@code removeDocumentVectors}
 * 也变成空实现，"是否真的清掉了向量"就无从观测（断言会恒假）。这里只替换向量模型，向量库、
 * 切块、元数据注入、清理全部走真实实现，断言才有效力。</p>
 *
 * <p><b>覆盖</b>：
 * <ol>
 *   <li>竞态主体（删除完全早于 addAll）→ 由<b>写入后复核</b>兜住，断言无残留切片；</li>
 *   <li>中间时序（① 清向量 &lt; addAll &lt; ② 删记录）→ 复核此刻读到的记录<b>还在</b>、不会清理，
 *       这一路只能靠 <b>③ 删后复扫</b>收尾；本用例断言正是 ③ 在起作用；</li>
 *   <li>正常路径不回归：解析完成后删除 → 切片被清理、记录逻辑删除。</li>
 * </ol>
 *
 * <p>三条用例分别把修复的三段机制（写入后复核 / 删后复扫 / 常规清扫）钉住；任何一段被删掉，
 * 对应用例即失败（已用"临时移除修复代码"的方式验证过用例确实会红，而非恒绿）。</p>
 *
 * <p><b>⚠️ 证据落差（审查 P2-4，务必知悉）</b>：本类跑在 {@code application-test.yml} 的
 * {@code rag.chroma.base-url: DISABLED} 下，即<b>进程内 {@code InMemoryEmbeddingStore}</b>
 * （{@code CopyOnWriteArrayList}，{@code addAll}/{@code removeAll} 在同一把锁下串行，
 * <b>读后写可见性天然成立</b>）。而三段式修复的整个正确性论证，都建立在"紧邻的
 * {@code removeAll(filter)} 必然能看见刚 {@code addAll} 的切片"之上 ——
 * <b>生产是 Chroma over HTTP，这个前提在 InMemory 下是被白送的</b>。
 * 也就是说：<b>本类证明的是"修复逻辑在理想向量库语义下成立"，不等于"在真实 Chroma 下也成立"</b>
 * （已核实 langchain4j 0.35.0 的 {@code ChromaEmbeddingStore} 确实实现了 {@code removeAll(Filter)}，
 * 故生产不会静默失效，但该语义无自动化覆盖）。<b>真实 Chroma 下的验证由 Issue #58 的人工复测承担。</b></p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherDocumentDeleteRaceTest {

    /** teacher01（seed id=1）任教的课程，课件上传/删除都要过归属校验 */
    private static final long COURSE_ID = 1L;

    /**
     * 固定的"向量"：mock 的 embedAll 与断言侧查询向量用同一个，余弦相似度恒为 1，
     * 于是按 docId 过滤后的命中数 = 该课件的切片数（与向量取值无关，只用于计数）。
     */
    private static final float[] FIXED_VECTOR = {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
    private static final Embedding QUERY_EMBEDDING = Embedding.from(FIXED_VECTOR);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Autowired
    private CourseDocumentService docService;

    @Autowired
    @Qualifier("ingestExecutor")
    private ThreadPoolTaskExecutor ingestExecutor;

    /** 仅用于清理本测试硬插入/落盘的数据，保证演示库不被污染 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 替换本地 BGE 模型：唯一目的是获得"addAll 之前"这个可控暂停点，顺带省掉 45MB 模型加载 */
    @MockBean
    private EmbeddingModel embeddingModel;

    /**
     * 监听真实切块服务：{@code removeDocumentVectors} 仍是真实现（否则"有没有清掉向量"无从观测），
     * 只是在用例 2 里把删除线程<b>卡在"① 清向量已做完、尚未执行 ② 删记录"</b>这一刻，
     * 以便把 addAll 精确插进这两步之间。
     */
    @SpyBean
    private DocumentIngestionService ingestionService;

    private CountDownLatch parseEntered;
    private CountDownLatch parseRelease;

    /** 用例 2 专用：① 已完成 / 放行继续 ② ③ */
    private CountDownLatch firstSweepDone;
    private CountDownLatch firstSweepRelease;
    private final AtomicBoolean pauseAfterFirstSweep = new AtomicBoolean(false);

    private String teacherToken;
    private final List<Long> createdDocIds = new ArrayList<>();
    private final List<String> uploadedFilePaths = new ArrayList<>();
    private ExecutorService deleteCaller;

    @BeforeEach
    void setUp() throws Exception {
        parseEntered = new CountDownLatch(1);
        parseRelease = new CountDownLatch(1);
        firstSweepDone = new CountDownLatch(1);
        firstSweepRelease = new CountDownLatch(0);   // 默认不暂停：只有用例 2 会重置为 1
        pauseAfterFirstSweep.set(false);
        createdDocIds.clear();
        uploadedFilePaths.clear();

        when(embeddingModel.embedAll(anyList())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            // 通知测试：切块线程已进入"向量化"阶段，即 addAll 之前
            parseEntered.countDown();
            if (!parseRelease.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试未在 20s 内放行切块线程");
            }
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(FIXED_VECTOR))
                    .toList());
        });

        // 真实清扫照常执行；仅在用例 2 打开开关时，于"① 之后"暂停一次
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (pauseAfterFirstSweep.compareAndSet(true, false)) {
                firstSweepDone.countDown();
                if (!firstSweepRelease.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试未在 20s 内放行删除线程");
                }
            }
            return result;
        }).when(ingestionService).removeDocumentVectors(any(Long.class));

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"teacher01\",\"password\":\"123456\"}"))
                .andReturn();
        JsonNode loginBody = bodyOf(login);
        assertEquals(200, loginBody.path("code").asInt(), "测试前提：teacher01 真实登录成功");
        teacherToken = loginBody.path("data").path("token").asText();
    }

    @AfterEach
    void cleanUp() {
        // 先放行并等在途任务结束，避免清理之后还有线程往向量库/数据库写
        parseRelease.countDown();
        firstSweepRelease.countDown();
        try {
            awaitIngestIdle();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (deleteCaller != null) {
            deleteCaller.shutdownNow();
            deleteCaller = null;
        }
        // 清向量切片
        for (Long docId : createdDocIds) {
            embeddingStore.removeAll(new IsEqualTo("docId", String.valueOf(docId)));
        }
        // 硬删记录（逻辑删除会留下不可见行，长期跑会把演示库堆脏）
        for (Long docId : createdDocIds) {
            jdbcTemplate.update("DELETE FROM course_document WHERE id = ?", docId);
        }
        // 删落盘文件（删除接口不负责删磁盘文件）
        for (String path : uploadedFilePaths) {
            File f = new File(path);
            if (f.exists() && !f.delete()) {
                fail("清理失败：无法删除测试上传文件 " + path);
            }
        }
    }

    @Test
    @DisplayName("#58 竞态复现：删课件发生在 addAll 之前 → 在途线程不得把向量写回（无幽灵切片）")
    void deleteDuringParsingLeavesNoGhostVectors() throws Exception {
        long docId = upload("竞态复现.md");
        CourseDocument persisted = docService.getById(docId);
        assertNotNull(persisted, "上传后应能查到课件记录");
        assertEquals(CourseDocument.STATUS_PARSING, persisted.getParseStatus(),
                "上传即落库为 PARSING（切块任务紧接着提交）");

        // 复现步骤 1：切块线程已进入向量化阶段，正卡在 embeddingStore.addAll 之前
        assertTrue(parseEntered.await(15, TimeUnit.SECONDS),
                "切块线程未在预期时间内进入向量化阶段");

        // 复现步骤 2：暂停期间走接口删除课件 —— 必须成功（删除不应因"解析中"被拒绝）
        MvcResult del = mockMvc.perform(delete("/api/teacher/docs/{id}", docId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andReturn();
        assertEquals(200, bodyOf(del).path("code").asInt(),
                "解析中的课件应可被删除，实际响应：" + bodyOf(del));
        assertNull(docService.getById(docId), "接口返回成功后记录应已被逻辑删除");

        // 复现步骤 3：放行切块线程，让它完成 addAll 与"写入后复核"
        parseRelease.countDown();
        awaitIngestIdle();

        // 复现步骤 4：该 docId 不得残留任何切片
        //
        // ⚠️ 本用例"能钉住「写入后复核」"依赖一个非显然的前提（审查指出，务必保留说明）：
        //    此处删除已在 addAll 之前完成了 ①②③，放行后切块线程写入向量；若人为删掉
        //    「写入后复核」，它会直接调用 markChunked —— 而 markChunked 走 MyBatis-Plus
        //    updateById，对已逻辑删除的行**影响 0 行且静默不抛异常**，因此**不会**掉进
        //    catch 分支被那里顺手的清理兜住，向量才留下来、断言才红。
        //    反过来说：**若日后把 markChunked 改成"更新不到行就抛异常"，异常会进 catch →
        //    触发清理 → 本用例仍绿**，这条用例就静默失去钉住能力（且没人会知道）。
        //    改 markChunked 语义时，必须回来复核本用例的变异测试是否依然成立。
        assertEquals(0, segmentsOf(docId),
                "删除后向量库仍残留该 docId 的切片 —— 幽灵参考资料复现（学生仍能检索到已删课件）");
    }

    @Test
    @DisplayName("#58 中间时序：①清向量 < addAll < ②删记录 → 写入后复核读不到删除事实，须靠 ③删后复扫收尾")
    void sweepAfterRecordDeletionClosesMiddleWindow() throws Exception {
        long docId = upload("中间时序.md");
        assertTrue(parseEntered.await(15, TimeUnit.SECONDS), "切块线程应已进入向量化阶段");

        // 打开暂停开关：删除线程做完 ①（此刻 addAll 还没发生 → ① 清到的是空集合）
        // 就会停在 ② 之前
        firstSweepDone = new CountDownLatch(1);
        firstSweepRelease = new CountDownLatch(1);
        pauseAfterFirstSweep.set(true);
        deleteCaller = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-delete-caller"));
        Future<MvcResult> deleteFuture = deleteCaller.submit(() ->
                mockMvc.perform(delete("/api/teacher/docs/{id}", docId)
                                .header("Authorization", "Bearer " + teacherToken))
                        .andReturn());

        assertTrue(firstSweepDone.await(15, TimeUnit.SECONDS),
                "删除线程未在预期时间内完成 ① 清向量");

        // 让 addAll 落进 ① 与 ② 之间：此后"写入后复核"读到的记录**仍然存在**（② 尚未执行），
        // 因此复核不会清理——正是本用例要覆盖的漏网时序
        parseRelease.countDown();
        awaitIngestIdle();
        assertTrue(segmentsOf(docId) > 0,
                "该时序下在途线程应已把向量写回（复核那时读到的记录还在），实际切片数=0，用例前提不成立");

        // 放行删除线程：它继续执行 ② 删记录、③ 删后复扫
        firstSweepRelease.countDown();
        MvcResult del = deleteFuture.get(20, TimeUnit.SECONDS);
        assertEquals(200, bodyOf(del).path("code").asInt(),
                "解析中的课件应可被删除，实际响应：" + bodyOf(del));
        assertNull(docService.getById(docId), "记录应已被逻辑删除");

        assertEquals(0, segmentsOf(docId),
                "① 与 ② 之间写回的切片未被清理 —— 说明 delete() 的「③ 删后复扫」缺失或被破坏");
    }

    @Test
    @DisplayName("#58 正常路径不回归：解析完成后删除 → 切片被清理、记录逻辑删除")
    void normalParseThenDeleteStillWorks() throws Exception {
        parseRelease.countDown(); // 本次不制造竞态：立刻放行

        long docId = upload("正常路径.md");
        // 本用例记录会保留，故直接用语义更强的"轮询业务终态"（审查 P2-3：不要依赖线程池空闲采样）
        awaitParseSettled(docId);

        CourseDocument afterIngest = docService.getById(docId);
        assertNotNull(afterIngest, "正常路径下课件记录应保留");
        assertEquals(CourseDocument.STATUS_CHUNKED, afterIngest.getParseStatus(),
                "写入后复核不得误伤正常路径：状态应推进到 CHUNKED");
        assertTrue(afterIngest.getChunkCount() != null && afterIngest.getChunkCount() > 0,
                "正常路径应写入切片数，实际：" + afterIngest.getChunkCount());
        assertTrue(segmentsOf(docId) > 0, "正常路径下向量库应有该课件的切片");

        // 走一次正常删除
        MvcResult del = mockMvc.perform(delete("/api/teacher/docs/{id}", docId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andReturn();
        assertEquals(200, bodyOf(del).path("code").asInt(), "正常删除应成功");

        assertNull(docService.getById(docId), "删除后记录应查不到");
        assertEquals(0, segmentsOf(docId), "删除后切片应被级联清理");
    }

    // ==================== 辅助 ====================

    /** 走真实上传接口，返回 docId；顺带登记落盘路径供清理 */
    private long upload(String fileName) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, "text/markdown",
                ("# " + fileName + "\n虚拟内存与页面置换算法：FIFO、LRU、OPT。").getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/teacher/docs/upload")
                        .file(file)
                        .param("courseId", String.valueOf(COURSE_ID))
                        .header("Authorization", "Bearer " + teacherToken))
                .andReturn();

        JsonNode body = bodyOf(result);
        assertEquals(200, body.path("code").asInt(), "上传应成功，实际响应：" + body);
        long docId = body.path("data").asLong();
        assertTrue(docId > 0, "上传应返回正 docId，实际：" + body);

        createdDocIds.add(docId);
        CourseDocument doc = docService.getById(docId);
        assertNotNull(doc, "上传后应能查到课件记录");
        assertNotNull(doc.getFilePath(), "落库应有磁盘路径，供清理");
        uploadedFilePaths.add(doc.getFilePath());
        return docId;
    }

    /** 按 docId 统计向量库中的切片数（与向量取值无关，只做存在性计数） */
    private int segmentsOf(long docId) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(QUERY_EMBEDDING)
                .filter(new IsEqualTo("docId", String.valueOf(docId)))
                .maxResults(100)
                .minScore(0.0)
                .build();
        return embeddingStore.search(request).matches().size();
    }

    /**
     * 等待在途切块任务结束。
     *
     * <p><b>为什么不能称为"确定性的信号"</b>（审查 P2-3）：{@code ThreadPoolExecutor.addWorker}
     * 会把首个任务作为 {@code firstTask} 直接交给新建的 Worker（<b>从不入队</b>），而
     * {@code runWorker} 先 {@code w.unlock()} 再在新线程里 {@code w.lock()}，而
     * {@code getActiveCount()} 统计的正是 {@code w.isLocked()}。因此在 {@code t.start()} 与
     * 新线程真正拿到锁之间，{@code queue.isEmpty() && activeCount == 0} 会<b>瞬时同时成立</b>——
     * 单次采样可能提前返回，让后续断言读到尚未推进的状态（用例 3 因此存在真实 flaky 路径，
     * 目前被 {@code upload()} 里那次 MySQL 往返掩盖，CI 高负载下会暴露）。
     * 故这里要求该条件<b>连续两次采样（间隔 50ms）都成立</b>才判定空闲。</p>
     *
     * <p>若调用方在等待时记录仍然存在（未删除），优先用语义更强的
     * {@link #awaitParseSettled(long)}。</p>
     */
    private void awaitIngestIdle() throws InterruptedException {
        ThreadPoolExecutor pool = ingestExecutor.getThreadPoolExecutor();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int consecutiveIdle = 0;
        while (System.nanoTime() < deadline) {
            if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                if (++consecutiveIdle >= 2) {
                    return;
                }
            } else {
                consecutiveIdle = 0;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        fail("等待切块线程池空闲超时，在途任务未结束");
    }

    /**
     * 轮询业务终态：等 {@code parse_status} 进入 {@code CHUNKED} / {@code FAILED}，或记录已消失。
     *
     * <p>比等线程池空闲语义更强、也更抗抖（直接盯业务结果而非线程状态）。
     * 仅适用于"记录不会被删掉"的用例 —— 竞态用例里记录会被逻辑删除、状态永不推进，
     * 那里只能用 {@link #awaitIngestIdle()}。超时即 {@code fail}，不会静默通过。</p>
     */
    private void awaitParseSettled(long docId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            CourseDocument doc = docService.getById(docId);
            if (doc == null
                    || CourseDocument.STATUS_CHUNKED.equals(doc.getParseStatus())
                    || CourseDocument.STATUS_FAILED.equals(doc.getParseStatus())) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        fail("等待课件进入终态（CHUNKED/FAILED）超时, docId=" + docId);
    }

    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
