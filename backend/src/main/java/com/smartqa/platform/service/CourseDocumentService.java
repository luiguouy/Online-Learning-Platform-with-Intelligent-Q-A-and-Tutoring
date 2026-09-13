package com.smartqa.platform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartqa.platform.entity.CourseDocument;

import java.util.List;

/**
 * 课件服务。
 *
 * <p>状态流转由本服务的 markXxx 方法统一收口，避免调用方直接 updateById 时
 * 漏字段（例如忘记把 errorMsg 清空，导致前端永远显示上次的失败原因）。</p>
 *
 * @author 成员 B
 */
public interface CourseDocumentService extends IService<CourseDocument> {

    /**
     * 查询某课程下的课件列表（按上传时间倒序），供教师端课件管理页使用。
     *
     * @param courseId 课程 ID
     * @return 课件列表
     */
    List<CourseDocument> listByCourse(Long courseId);

    /**
     * 状态置为 PARSING 并清空分块数与上次的失败原因。
     *
     * @param docId 课件 ID
     */
    void markParsing(Long docId);

    /**
     * 状态置为 CHUNKED 并写入分块数。
     *
     * @param docId      课件 ID
     * @param chunkCount 切块数量
     */
    void markChunked(Long docId, int chunkCount);

    /**
     * 状态置为 FAILED 并写入失败原因。
     *
     * <p>⚠️ 切块异常时必须调用本方法，否则前端会永远显示"切块向量化中"。</p>
     *
     * @param docId    课件 ID
     * @param errorMsg 失败原因，超长会被截断到列长度 500
     */
    void markFailed(Long docId, String errorMsg);
}
