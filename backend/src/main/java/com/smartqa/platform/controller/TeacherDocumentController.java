package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.entity.CourseDocument;
import com.smartqa.platform.service.CourseDocumentService;
import com.smartqa.platform.service.CourseService;
import com.smartqa.platform.service.rag.DocumentIngestionService;
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

    private final CourseDocumentService docService;
    private final CourseService courseService;
    private final DocumentIngestionService ingestionService;

    /** 专用线程池（AsyncThreadPoolConfig 中定义），禁止使用默认公共池 */
    @Resource(name = "sseExecutor")
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
        file.transferTo(dest);

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
        ingestionService.removeDocumentVectors(id);
        docService.removeById(id);
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

        // 1. 先清旧向量，否则重建会产生重复切片
        ingestionService.removeDocumentVectors(id);

        // 2. 状态回到 PARSING，分块数与上次失败原因清零
        docService.markParsing(id);

        // 3. 异步重新切块
        submitIngestion(id, doc.getCourseId(), doc.getFileName(), doc.getFilePath(), teacherId);

        return Result.success(true);
    }

    /**
     * 在 sseExecutor 中异步执行「切块向量化 → 状态回写」。
     *
     * <p>状态回写由本异步块自己完成（成功置 CHUNKED + 分块数，异常置 FAILED + errorMsg），
     * 成员 A 不需要回调任何方法。异常必须兜住，否则前端会永远显示"切块向量化中"。</p>
     */
    private void submitIngestion(Long docId, Long courseId, String fileName,
                                 String filePath, Long uploadedBy) {
        CompletableFuture.runAsync(() -> {
            try (InputStream in = new FileInputStream(filePath)) {
                int chunks = ingestionService.ingest(in, fileName, courseId, docId, uploadedBy);
                docService.markChunked(docId, chunks);
            } catch (Exception e) {
                log.error("课件切块向量化失败, docId={}, fileName={}", docId, fileName, e);
                docService.markFailed(docId, e.getMessage());
            }
        }, asyncExecutor);
    }

    /** 取小写扩展名（已通过白名单校验，不会为 null） */
    private String extractExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1).toLowerCase();
    }
}
