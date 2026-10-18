package com.smartqa.platform.controller;

import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.service.rag.DocumentIngestionService;
import com.smartqa.platform.vo.AttachmentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 学生端问答附件上传控制器（支持图片与参考文档）。
 *
 * <p>安全边界（改动前必读，详见 dev-docs/SECURITY_DECISIONS.md）：</p>
 * <ul>
 *   <li><b>SEC-2</b>：本目录 {@code attachments/} 经静态映射公开可访问（低敏内容，学生自己上传的图/文），
 *       因此文件名<strong>必须带完整 UUID</strong>，可猜性全靠这段熵，严禁截断；
 *       同时上传根目录下的课件原件<strong>不在</strong>任何静态映射内。</li>
 *   <li>文档内容提取一律走 {@link DocumentIngestionService#extractPlainTextWithLimits}，
 *       <b>不得</b>在本类内另起 Tika 解析 —— 那会绕过输入字节上限与超时守卫（见该方法 javadoc）。</li>
 *   <li>响应文案不得拼接 {@code e.getMessage()}：落盘失败信息含服务器绝对路径与命名规则。</li>
 * </ul>
 */
@Slf4j
@Tag(name = "问答附件上传")
@RestController
@RequestMapping("/api/qa/upload")
@RequiredArgsConstructor
public class AttachmentController {

    private static final Pattern IMAGE_EXT = Pattern.compile("(?i).+\\.(jpg|jpeg|png|gif|webp)$");
    private static final Pattern DOC_EXT = Pattern.compile("(?i).+\\.(pdf|docx|md|txt)$");

    /** 图片上限 5MB：与头像口径一致（{@code UserController.MAX_AVATAR_BYTES}），答疑贴图没有理由更大 */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /**
     * 文档上限 20MB：与 {@code rag.ingest.max-file-bytes} 同值。
     * multipart 全局 50MB 只是容器兜底，业务侧必须自己给出更小的闸，
     * 否则一次上传就能写满磁盘或喂进一条长时间解析。
     */
    private static final long MAX_DOC_BYTES = 20L * 1024 * 1024;

    /** 提取文本回传给大模型作上下文的字符预算（原实现即 2000 字截断） */
    private static final int EXTRACT_CHAR_BUDGET = 2000;

    @Value("${file.upload-dir}")
    private String uploadDir;

    /** 文档解析的唯一入口：三层安全上限（输入字节 / 提取字符 / 超时）都在这条链路上 */
    private final DocumentIngestionService ingestionService;

    @PostMapping("/attachment")
    @Operation(summary = "学生上传问答辅助附件（图片/文档）")
    public Result<AttachmentVO> uploadAttachment(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException("文件名非法");
        }

        String lowerName = originalName.toLowerCase(Locale.ROOT);
        boolean isImage = IMAGE_EXT.matcher(lowerName).matches();
        boolean isDoc = DOC_EXT.matcher(lowerName).matches();

        if (!isImage && !isDoc) {
            throw new BusinessException("仅支持常见图片 (JPG/PNG/GIF/WEBP) 或文档 (PDF/DOCX/MD/TXT) 格式");
        }

        // 分类大小闸（先于落盘判断，避免把超限文件写进磁盘再删）
        long maxBytes = isImage ? MAX_IMAGE_BYTES : MAX_DOC_BYTES;
        if (file.getSize() > maxBytes) {
            throw new BusinessException(isImage
                    ? "图片不能超过 " + (MAX_IMAGE_BYTES / 1024 / 1024) + "MB"
                    : "文档不能超过 " + (MAX_DOC_BYTES / 1024 / 1024) + "MB，请拆分后重试");
        }

        // 统一附件存储目录：{uploadDir}/attachments —— 与 SaTokenConfigure 里放行的静态子目录一一对应
        File dir = new File(uploadDir, "attachments");
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("创建附件目录失败: {}", dir.getAbsolutePath());
            throw new BusinessException(500, "附件目录初始化失败");
        }

        String cleanName = new File(originalName).getName();
        // SEC-2：完整 UUID，不可截断。该目录可匿名访问，随机后缀是唯一的防枚举手段。
        String savedFileName = System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "") + "_" + cleanName;
        File targetFile = new File(dir, savedFileName);

        try {
            file.transferTo(targetFile);
        } catch (Exception e) {
            // 不得把 e.getMessage() 拼进响应：其中含上传目录绝对路径与落盘命名规则，细节全进日志
            log.error("保存上传附件失败, fileName={}, dest={}", originalName, targetFile.getAbsolutePath(), e);
            throw new BusinessException(500, "附件保存失败，请稍后重试或减小文件后重新上传");
        }

        String fileUrl = "/uploads/attachments/" + savedFileName;
        String extractedText = isDoc ? extractTextForContext(targetFile, cleanName) : "";

        AttachmentVO vo = AttachmentVO.builder()
                .fileUrl(fileUrl)
                .fileName(cleanName)
                .fileType(isImage ? "image" : "doc")
                .fileSize(file.getSize())
                .extractedText(extractedText)
                .build();

        return Result.success(vo);
    }

    /**
     * 为 AI 问答上下文提取纯文本：<b>委托给课件链路的同一套三层上限</b>。
     *
     * <p>提取失败或超限一律降级为空串而不阻断上传 —— 附件文本只是辅助上下文，
     * 文件本身已经落盘成功，让学生拿到 500 反而更糟。真实原因全部进日志。</p>
     */
    private String extractTextForContext(File file, String displayName) {
        try (InputStream in = java.nio.file.Files.newInputStream(file.toPath())) {
            return ingestionService.extractPlainTextWithLimits(in, displayName, EXTRACT_CHAR_BUDGET);
        } catch (Exception e) {
            log.warn("[Attachment] 附件文本提取失败（已降级为无上下文）: {}, 原因: {}",
                    displayName, e.getMessage());
            return "";
        }
    }
}
