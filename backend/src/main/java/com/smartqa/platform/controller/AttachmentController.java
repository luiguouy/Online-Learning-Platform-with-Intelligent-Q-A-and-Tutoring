package com.smartqa.platform.controller;

import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.vo.AttachmentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
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
 */
@Slf4j
@Tag(name = "问答附件上传")
@RestController
@RequestMapping("/api/qa/upload")
public class AttachmentController {

    private static final Pattern IMAGE_EXT = Pattern.compile("(?i).+\\.(jpg|jpeg|png|gif|webp)$");
    private static final Pattern DOC_EXT = Pattern.compile("(?i).+\\.(pdf|docx|md|txt)$");

    @Value("${file.upload-dir}")
    private String uploadDir;

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

        // 统一附件存储目录：{uploadDir}/attachments
        File dir = new File(uploadDir, "attachments");
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("创建附件目录失败: {}", dir.getAbsolutePath());
            throw new BusinessException(500, "附件目录初始化失败");
        }

        String cleanName = new File(originalName).getName();
        String savedFileName = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "_" + cleanName;
        File targetFile = new File(dir, savedFileName);

        try {
            file.transferTo(targetFile);
        } catch (Exception e) {
            log.error("保存上传附件失败", e);
            throw new BusinessException(500, "保存附件文件失败: " + e.getMessage());
        }

        String fileUrl = "/uploads/attachments/" + savedFileName;
        String extractedText = "";

        if (isDoc) {
            extractedText = extractTextFromDoc(targetFile);
        }

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
     * 使用 Apache Tika 提取文档文本（提取最多 2000 字供 AI 问答上下文参考）
     */
    private String extractTextFromDoc(File file) {
        try (InputStream in = java.nio.file.Files.newInputStream(file.toPath())) {
            Parser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(100000);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            parser.parse(in, handler, metadata, context);
            String content = handler.toString();
            if (content == null) return "";
            content = content.replaceAll("\\s+", " ").trim();
            if (content.length() > 2000) {
                return content.substring(0, 2000) + "...(后文省略)";
            }
            return content;
        } catch (Exception e) {
            log.warn("提取文档文本出现警告/异常: {}", e.getMessage());
            return "";
        }
    }
}
