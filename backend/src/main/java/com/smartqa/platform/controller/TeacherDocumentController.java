package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.entity.CourseDocument;
import com.smartqa.platform.service.CourseDocumentService;
import com.smartqa.platform.service.CourseService;
import com.smartqa.platform.service.rag.DocumentIngestionService;
import com.smartqa.platform.service.rag.SemanticCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 教师端课件管理接口：上传 / 列表 / 删除 / 重建索引。
 *
 * <p>路径与 {@code TEAM_WORK_DIVISION.md} 接口矩阵逐字符一致，成员 D 的教师端已按此写死。</p>
 *
 * <p>与成员 A 的边界：向量化由 A 的
 * {@link DocumentIngestionService#ingest(InputStream, String, Long, Long, Long)} 完成，
 * B 只负责落盘、落库、状态推进与状态回写。</p>
 *
 * @author 成员 B
 */
@Slf4j
@Tag(name = "教师端-课件管理")
@RestController
@RequestMapping("/api/teacher/docs")
@RequiredArgsConstructor
public class TeacherDocumentController {

    /** 允许上传的课件扩展名白名单（大小写不敏感） */
    private static final Pattern ALLOWED_EXTENSION = Pattern.compile("(?i).+\\.(pdf|docx|md|txt)$");

    /**
     * 文件名长度上限（含扩展名）。
     *
     * <p>落盘路径形如 {@code {upload-dir}/{courseId}/{时间戳}_{文件名}}，时间戳前缀约 14 位。
     * Windows 单个路径分量上限 255 字符，因此文件名必须留出余量：
     * 超长文件名会直接触发 {@code FileNotFoundException(文件名、目录名或卷标语法不正确)}
     * 并被兜底成 500 —— 这是用户输入问题，应当前置拦截为 400。</p>
     */
    private static final int MAX_FILE_NAME_LENGTH = 200;

    private final CourseDocumentService docService;
    private final CourseService courseService;
    private final DocumentIngestionService ingestionService;
    private final SemanticCacheService semanticCacheService;

    /** 课件切块专用线程池（AsyncThreadPoolConfig 中定义，与 SSE 问答流隔离），禁止使用默认公共池 */
    @Resource(name = "ingestExecutor")
    private Executor asyncExecutor;

    /** 绝对上传目录，如 ${user.home}/smartqa/uploads/ —— 严禁相对路径 */
    @Value("${file.upload-dir}")
    private String uploadDir;

    @PostMapping("/upload")
    @Operation(summary = "课件文件上传并异步触发切块向量化")
    public Result<Long> upload(@RequestParam("courseId") Long courseId,
                               @RequestParam("file") MultipartFile file) throws IOException {
        // 0. 越权保护：教师只能往自己任课的课程里传课件
        Long teacherId = StpUtil.getLoginIdAsLong();
        courseService.assertTeacherOwnsCourse(courseId, teacherId);

        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不可为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !ALLOWED_EXTENSION.matcher(originalName).matches()) {
            throw new BusinessException("仅支持 PDF / DOCX / MD / TXT 格式");
        }
        // 【安全】路径穿越防护：文件名里出现目录分隔符或 ".." 一律拒绝
        if (originalName.contains("/") || originalName.contains("\\") || originalName.contains("..")) {
            throw new BusinessException("文件名非法");
        }
        // 【边界】文件名过长：落盘会拼成 {时间戳}_{文件名}，Windows 单路径分量上限 255，
        //        超限时 transferTo 抛 IOException 被兜底成 500。这里前置拦成 400。
        if (originalName.length() > MAX_FILE_NAME_LENGTH) {
            throw new BusinessException("文件名过长（最多 " + MAX_FILE_NAME_LENGTH + " 个字符），请重命名后重试");
        }
        // 【安全】再剥离一次目录，只保留纯文件名（双保险）
        String safeName = new File(originalName).getName();

        // 1. 落盘。必须用配置的绝对路径：相对路径在 jar 运行/重启后会丢文件。
        String baseDir = (uploadDir.endsWith("/") || uploadDir.endsWith("\\")) ? uploadDir : uploadDir + "/";
        String savedPath = baseDir + courseId + "/" + System.currentTimeMillis() + "_" + safeName;
        File dest = new File(savedPath);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new BusinessException(500, "上传目录创建失败，请检查 file.upload-dir 配置");
        }
        try {
            file.transferTo(dest);
        } catch (IOException e) {
            // 兜底：非法字符、路径超长、磁盘满、权限不足等落盘失败，统一转成可读提示。
            // 【审查 M1】不得把 e.getMessage() 拼进响应：Windows 下 FileNotFoundException 的
            // message 含上传目录绝对路径与落盘命名规则（时间戳前缀），会泄漏服务器路径，
            // 性同 dev 上刚移除 filePath 下发的加固（2b9a78c）。细节全进日志，响应只给可执行提示。
            log.error("课件落盘失败, courseId={}, fileName={}, dest={}", courseId, originalName, savedPath, e);
            throw new BusinessException(500, "文件保存失败，请重命名文件（避免特殊字符）后重试，若仍失败请联系管理员");
        }

        // 2. 落库并直接把状态推进到 PARSING（切块任务紧接着提交，不存在真正的排队期）
        CourseDocument doc = CourseDocument.builder()
                .courseId(courseId)
                .fileName(originalName)
                .filePath(savedPath)
                .fileSize(file.getSize())
                .fileType(extractExtension(originalName))
                .chunkCount(0)
                .parseStatus(CourseDocument.STATUS_PARSING)
                .errorMsg("")
                .build();
        docService.save(doc);

        Long docId = doc.getId();
        if (docId == null) {
            throw new BusinessException(500, "课件落库失败：未取到主键，请检查 id-type 配置");
        }

        // 3. 异步调用成员 A 的切块向量化。
        //    ⚠️ 异步线程里取不到 Sa-Token 登录态，所以 uploadedBy 必须在请求线程先取好再传下去。
        submitIngestion(docId, courseId, originalName, savedPath, teacherId);

        return Result.success(docId);
    }

    @GetMapping("/list")
    @Operation(summary = "课件列表（教师端课件管理页，按上传时间倒序）")
    public Result<List<CourseDocument>> list(@RequestParam("courseId") Long courseId) {
        Long teacherId = StpUtil.getLoginIdAsLong();
        courseService.assertTeacherOwnsCourse(courseId, teacherId);
        return Result.success(docService.listByCourse(courseId));
    }

    /**
     * 删除课件（级联清理向量库，防止幽灵参考资料）。
     *
     * <p><b>⚠️ 本方法必须保持「无事务」</b>（本类与全局均未使用 {@code @Transactional}，
     * <b>请勿给它加上</b>）：② 的逻辑删除依赖 {@code removeById} 立即 autocommit，
     * 在途切块线程的「写入后复核」才能读到删除事实；③ 也正是借此顺序才闭合窗口。
     * 若加上 {@code @Transactional}，② 在提交前对其它连接不可见，时序退化为
     * {@code ① → ③ → (addAll) → 复核读到记录仍在 → 不清理 → (提交)}，
     * 幽灵切片将<b>永久残留</b>；而且现有 3 条竞态用例仍会全绿（测试同样无事务），
     * 照不出这个回归 —— 即"看起来更严谨"的重构会静默击穿 #58 修复。</p>
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除课件（级联清理向量库，防止幽灵参考资料）")
    public Result<Boolean> delete(@PathVariable("id") Long id) {
        Long teacherId = StpUtil.getLoginIdAsLong();
        CourseDocument doc = docService.getById(id);
        if (doc == null) {
            throw new BusinessException(404, "课件不存在");
        }
        courseService.assertTeacherOwnsCourse(doc.getCourseId(), teacherId);

        // 关键：MySQL 记录删掉后必须同步删除向量库中该 docId 的全部切块，
        // 否则课件没了但学生提问仍能检索到它的片段（幽灵参考资料）。
        //
        // 【Issue #58 竞态】删除允许发生在"解析中"，而在途切块线程是**切完全部片段后
        // 一次性** embeddingStore.addAll(...)。若本方法的清向量动作先于那次 addAll，
        // 就成了"先清了个空、随后被写回"的幽灵切片。三步顺序不可调换：
        //   ① 前置清扫 —— 向量库异常时在"尚未改变任何业务状态"前失败，用户可原样重试；
        //   ② 逻辑删除记录（is_deleted=1）—— 这是给在途切块线程的「作废」信号，
        //      必须早于 ③：否则在途线程的"写入后复核"读不到删除事实（TOCTOU，仅靠
        //      "写入前检查记录是否存在"无法覆盖）；
        //   ③ 删后复扫 —— 关闭 ①→② 之间落入的 addAll（在途线程恰好在那一段完成写入时，
        //      ① 清到的是空集合），使本接口自身闭环，不单独依赖异步侧的复核。
        // 在途线程侧的兜底见 submitIngestion 的「写入后复核」。
        ingestionService.removeDocumentVectors(id);   // ①
        docService.removeById(id);                    // ② 逻辑删除
        semanticCacheService.invalidateCourse(doc.getCourseId()); // 刷新该课程语义缓存
        // 【审查 P2-2】③ 失败不得让整个删除接口回 500：删除这一业务事实（②）已经成立，
        // 回 500 会让教师误以为没删掉而重复上传，反而放大残留；且记录已逻辑删除，
        // 重试只会拿到 404"课件不存在"，再没有任何接口能触发这次清理。
        // 故降级为告警日志 + 仍返回业务成功；残留切片的人工/对账清理见 Issue #62。
        try {
            ingestionService.removeDocumentVectors(id);   // ③
        } catch (Throwable e) {
            // 【Issue #74 §三】同 submitIngestion：这一步也可能抛 Error（依赖缺类 / 原生库缺失），
            // catch (Exception) 接不到就会让本次删除以 500 收场 —— 而"删除"这一业务事实在 ② 已成立，
            // 回 500 会让教师误以为没删掉而重复上传，正是本段注释要避免的情形。
            log.error("[#58] 删后复扫失败，可能存在残留切片需人工清理, docId={}", id, e);
        }

        return Result.success(true);
    }

    @PostMapping("/{id}/reindex")
    @Operation(summary = "重建课件索引：清旧向量 → 重新切块向量化（异步）")
    public Result<Boolean> reindex(@PathVariable("id") Long id) {
        Long teacherId = StpUtil.getLoginIdAsLong();
        CourseDocument doc = docService.getById(id);
        if (doc == null) {
            throw new BusinessException(404, "课件不存在");
        }
        courseService.assertTeacherOwnsCourse(doc.getCourseId(), teacherId);
        semanticCacheService.invalidateCourse(doc.getCourseId());

        // 1. 状态 CAS：仅当课件处于终态(CHUNKED/FAILED)才允许重建。抢到锁才继续，
        //    避免对“解析中”课件并发重复提交切块，也避免“删旧向量”与“在途写入”交错产生重复/幽灵切片。
        if (!docService.markParsingIfSettled(id)) {
            throw new BusinessException(409, "课件正在解析中或状态已变化，暂不能重建索引");
        }

        // 2. 再清旧向量，否则重建会产生重复切片（CAS 已成功，此刻无其他在途切块）
        //
        // 【Issue #57 §一】清理失败绝不能让状态停在 PARSING：上面 markParsingIfSettled 只放行
        //   CHUNKED/FAILED，若此处抛异常使状态留在 PARSING，该课件将**永久无法再次重建**（自锁）：
        //   再点一次会被 409 拒、刷新无效，而前端轮询到 2 分钟自行停止后显示的仍是
        //   “切块向量化中”，教师根本看不出已经卡死。故这里显式回置 FAILED（终态），
        //   既让用户看到失败原因，又因 FAILED 在 CAS 放行集合内而可以重试。
        //
        // ⚠️ 与 delete() 的「③ 删后复扫」处理方式**刻意相反**，不要照着那边改：
        //   delete() 里“删除”这一业务事实已经成立，③ 只是补偿，所以失败只记 ERROR 仍返回业务成功；
        //   而这里的清理是重建的**必要前置**，没清干净就继续切块恰恰会产生重复切片，
        //   所以必须中止、回置失败并把原因告知用户。
        try {
            ingestionService.removeDocumentVectors(id);
        } catch (Throwable e) {
            // 【Issue #74 §三】捕获面必须是 Throwable：清理会因依赖缺类 / 原生库缺失抛 Error，
            // 而 catch (Exception) 漏掉它时，下面"回置 FAILED"这一步根本不会执行 —— 状态就停在
            // PARSING（正是本段注释要防的自锁）。
            log.error("[#57] reindex 清理旧向量失败，已回置 FAILED 以便重试, docId={}", id, e);
            try {
                docService.markFailed(id, "重建索引失败：清理旧向量未成功（向量库可能暂时不可用），请稍后重试");
            } catch (Throwable inner) {
                // 回置也失败：课件会停在 PARSING（即本方法要防的那个状态）。此时已无更好的选择，
                // 但两个异常都必须留在日志里且带 docId，否则人工也无法定位该课件。
                log.error("[#57] 回置 FAILED 亦失败，课件状态可能停留在 PARSING，需人工按 docId 修正, docId={}",
                        id, inner);
            }
            throw new BusinessException(500, "重建索引失败：旧向量清理未成功，请稍后重试");
        }

        // 3. 异步重新切块
        submitIngestion(id, doc.getCourseId(), doc.getFileName(), doc.getFilePath(), teacherId);

        return Result.success(true);
    }

    /**
     * 在 ingestExecutor 中异步执行「切块向量化 → 状态回写」。
     *
     * <p>注意池名：本任务跑在<b>课件切块专用池 {@code ingestExecutor}</b> 上，与 SSE 问答流的
     * {@code sseExecutor} 是<b>两个隔离的池</b>（见 {@code AsyncThreadPoolConfig}）。
     * 早期注释曾误写为 {@code sseExecutor}，排查切块积压时请查 {@code ingestExecutor} 的
     * 活跃数与队列，不要查错池。</p>
     *
     * <p>状态回写由本异步块自己完成（成功置 CHUNKED + 分块数，异常置 FAILED + errorMsg），
     * 成员 A 不需要回调任何方法。异常必须兜住，否则前端会永远显示"切块向量化中"。</p>
     */
    private void submitIngestion(Long docId, Long courseId, String fileName,
                                 String filePath, Long uploadedBy) {
        try {
            CompletableFuture.runAsync(() -> {
                try (InputStream in = new FileInputStream(filePath)) {
                    int chunks = ingestionService.ingest(in, fileName, courseId, docId, uploadedBy);

                    // 【Issue #58】写入后复核（整条链路对并发删除的最后兜底）。
                    // delete() 允许在解析中删除课件；若本任务的 addAll 恰好落在它 ①→③
                    // 两次清扫之间，向量就会以"幽灵切片"留在库中。addAll 之后再查一次记录
                    // 是否还在：记录已被逻辑删除（getById 返回 null，逻辑删除对它可见）
                    // 说明这次的切块结果已被作废 → 立即回收刚写入的向量，且不回写状态。
                    // ⚠️ 必须是"写后查"：写成"查后再写"只能缩小窗口，消除不了 TOCTOU。
                    if (docService.getById(docId) == null) {
                        ingestionService.removeDocumentVectors(docId);
                        log.warn("[#58] 课件在解析期间被删除，已回收刚写入的向量切片, docId={}, chunks={}",
                                docId, chunks);
                        return;
                    }
                    docService.markChunked(docId, chunks);
                } catch (Throwable e) {
                    // 【Issue #74 §三】这里必须是 Throwable，不能是 Exception —— 接不到 Error 就是静默失败。
                    // ingest() 只在 Tika 解析段借 Future.get() 把 Error 转成 Exception；其后的
                    // embedAll / addAll（splitEmbedAndStore）没有任何包装，UnsatisfiedLinkError
                    // （BGE/ONNX 原生库缺失）、NoClassDefFoundError（依赖缺类）会直接冒泡到这里。
                    // 而本任务是 CompletableFuture.runAsync 提交、返回值被丢弃、无人 join()/get()，
                    // CompletableFuture$AsyncSupply.run() 自身 catch (Throwable) 后只把异常存进结果对象
                    // （javap 实测异常表：from 35 to 46 target 49 Class java/lang/Throwable）——
                    // 既不打印也不传播。漏掉 Error 时课件会永久停在 PARSING：全仓无任何超时兜底，
                    // reindex 的状态 CAS 又只放行 CHUNKED/FAILED，该课件从此无法重建，只能重传。
                    log.error("课件切块向量化失败, docId={}, fileName={}", docId, fileName, e);
                    markFailedSafely(docId, e);
                }
            }, asyncExecutor);
        } catch (RejectedExecutionException e) {
            // ingestExecutor 采用 AbortPolicy：切块池打满时直接拒绝。这里兜住并把状态置为 FAILED，
            // 否则课件会永远卡在 PARSING（状态回写由异步任务负责，而任务根本没提交成功）。
            log.error("切块线程池饱和，拒绝入库 docId={}, fileName={}", docId, fileName, e);
            docService.markFailed(docId, "系统繁忙，切块任务队列已满，请稍后重新上传或重建索引");
        }
    }

    /**
     * 失败状态回写（带二次兜底）。
     *
     * <p>调用方已处在"捕获 {@code Throwable}"的兜底分支里，所以回写动作自身
     * （查库 / 清向量 / 更新状态）绝不能再让异常逸出 —— 逸出即回到"无人 join 的
     * {@code CompletableFuture} 静默吞异常"的老路，课件依旧停在 {@code PARSING}。</p>
     *
     * <p>文案分档：{@code Error}（框架 / 依赖级故障）给"系统组件异常"这一档，
     * 不把类名技术串直接展示给教师；普通 {@code Exception} 原样沿用 {@code ingest()}
     * 抛出的面向用户文案（#55 口径：三类上限与"无文字层"的具体原因都在里面，不可覆盖）。</p>
     */
    private void markFailedSafely(Long docId, Throwable cause) {
        String reason = (cause instanceof Error)
                ? "系统组件异常，切块向量化未能完成（SYS-INGEST-DEP），请稍后重试或联系管理员"
                : cause.getMessage();
        try {
            if (docService.getById(docId) == null) {
                // 课件已被删除：既不回写 FAILED（updateById 本就会影响 0 行、徒留误报日志），
                // 也顺手清掉可能已部分写入的切片，避免留下幽灵参考资料。
                ingestionService.removeDocumentVectors(docId);
                log.warn("[#58] 课件在解析期间被删除，跳过失败状态回写并清理残留切片, docId={}", docId);
            } else {
                docService.markFailed(docId, reason);
            }
        } catch (Throwable t) {
            // 极端情况（如向量库连 GET 都抛 Error）：状态可能仍停在 PARSING，
            // 但日志里必须留下可按 docId 人工定位的线索。
            log.error("[#74] 失败状态回写未成功，课件可能仍停留在 PARSING，需人工按 docId 修正, docId={}",
                    docId, t);
        }
    }

    /** 取小写扩展名（已通过白名单校验，不会为 null） */
    private String extractExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1).toLowerCase();
    }

    /**
     * 孤儿向量切片对账清理端点（Issue #62 根治）
     *
     * <p>供管理员或维护人员一键触发（也可按需接入定时器）：扫描已逻辑删除超过静默期的课件，
     * 强力清理向量库与 BM25 中残留的孤儿切块，彻底闭环删后复扫失败残留问题。</p>
     *
     * @param quietMinutes 静默期分钟数，默认 10
     * @return 成功清理的课件数
     */
    @Operation(summary = "孤儿向量切片对账清理（#62 根治）", description = "清理已逻辑删除超过静默期的课件残留切片")
    @PostMapping("/reconcile-deleted-vectors")
    public Result<Integer> reconcileDeletedVectors(@RequestParam(defaultValue = "10") int quietMinutes) {
        int count = docService.reconcileDeletedDocumentVectors(quietMinutes);
        return Result.success(count);
    }
}
