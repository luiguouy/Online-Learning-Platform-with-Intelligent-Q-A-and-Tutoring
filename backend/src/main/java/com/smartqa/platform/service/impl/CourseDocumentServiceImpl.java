package com.smartqa.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartqa.platform.entity.CourseDocument;
import com.smartqa.platform.mapper.CourseDocumentMapper;
import com.smartqa.platform.service.CourseDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 课件服务实现。
 *
 * @author 成员 B
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseDocumentServiceImpl extends ServiceImpl<CourseDocumentMapper, CourseDocument>
        implements CourseDocumentService {

    /** error_msg 列长度上限 */
    private static final int ERROR_MSG_MAX_LENGTH = 500;

    @Override
    public List<CourseDocument> listByCourse(Long courseId) {
        return list(Wrappers.<CourseDocument>lambdaQuery()
                .eq(CourseDocument::getCourseId, courseId)
                .orderByDesc(CourseDocument::getCreatedAt));
    }

    @Override
    public void markParsing(Long docId) {
        CourseDocument update = CourseDocument.builder()
                .id(docId)
                .parseStatus(CourseDocument.STATUS_PARSING)
                .chunkCount(0)
                .errorMsg("")
                .build();
        updateById(update);
    }

    @Override
    public boolean markParsingIfSettled(Long docId) {
        // 条件更新：只有当前状态为 CHUNKED / FAILED 时才置为 PARSING，
        // 并发重建时事二更新影响 0 行 → update() 返回 false，据此拒绝。
        return update(Wrappers.<CourseDocument>lambdaUpdate()
                .set(CourseDocument::getParseStatus, CourseDocument.STATUS_PARSING)
                .set(CourseDocument::getChunkCount, 0)
                .set(CourseDocument::getErrorMsg, "")
                .eq(CourseDocument::getId, docId)
                .in(CourseDocument::getParseStatus,
                        CourseDocument.STATUS_CHUNKED, CourseDocument.STATUS_FAILED));
    }

    @Override
    public void markChunked(Long docId, int chunkCount) {
        CourseDocument update = CourseDocument.builder()
                .id(docId)
                .parseStatus(CourseDocument.STATUS_CHUNKED)
                .chunkCount(Math.max(chunkCount, 0))
                .errorMsg("")
                .build();
        updateById(update);
    }

    @Override
    public void markFailed(Long docId, String errorMsg) {
        String msg = StringUtils.hasText(errorMsg) ? errorMsg : "解析失败（未捕获到具体原因）";
        if (msg.length() > ERROR_MSG_MAX_LENGTH) {
            msg = msg.substring(0, ERROR_MSG_MAX_LENGTH);
        }
        CourseDocument update = CourseDocument.builder()
                .id(docId)
                .parseStatus(CourseDocument.STATUS_FAILED)
                .errorMsg(msg)
                .build();
        updateById(update);
        log.warn("课件解析失败, docId={}, reason={}", docId, msg);
    }
}
