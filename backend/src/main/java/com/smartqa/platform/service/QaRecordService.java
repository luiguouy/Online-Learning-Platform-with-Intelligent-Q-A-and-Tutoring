package com.smartqa.platform.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartqa.platform.entity.QaRecord;
import com.smartqa.platform.vo.SseReferenceVO;

import java.util.List;

/**
 * 问答记录服务。
 *
 * @author 成员 B
 */
public interface QaRecordService extends IService<QaRecord> {

    /**
     * 【冻结契约 · Day 3 死线】流式传输完毕后保存提问与完整回复。
     *
     * <p>成员 A 在 SSE 推流结束后调用本方法，拿到 {@code recordId} 写进
     * {@code event: done} 数据包。</p>
     *
     * <p>⚠️ 返回值不能为 null —— A 会把它直接写进 {@code Map.of("recordId", recordId, ...)}，
     * 而 {@code Map.of} 不接受 null 值，会抛 NullPointerException。</p>
     * <p>⚠️ 方法签名已冻结，参数顺序与个数不得修改（A 的代码已按此编译）。</p>
     *
     * @param courseId   课程 ID
     * @param sessionId  会话 ID；传 0 或 null 时会兜底懒创建（正常流程下 A 应
     *                   先调 createSessionLazy 并把真实 sessionId 传进来）
     * @param question   学生提问原文
     * @param answer     AI 完整回答（Markdown）
     * @param references 命中的课件出处快照，可为空
     * @param latencyMs  模型生成耗时（毫秒）
     * @return 生成的问答记录 ID
     */
    Long saveStreamingRecord(Long courseId,
                             Long sessionId,
                             String question,
                             String answer,
                             List<SseReferenceVO> references,
                             long latencyMs);

    /**
     * 显式指定提问学生的保存重载。
     *
     * <p>在 sseExecutor 异步线程中 Sa-Token 的 ThreadLocal 登录态不可用，
     * A 在异步场景下请改用本方法并显式传入 userId。</p>
     *
     * @param userId     提问学生 ID
     * @param courseId   课程 ID
     * @param sessionId  会话 ID
     * @param question   学生提问原文
     * @param answer     AI 完整回答
     * @param references 命中的课件出处快照
     * @param latencyMs  模型生成耗时（毫秒）
     * @return 生成的问答记录 ID
     */
    Long saveStreamingRecordAs(Long userId,
                               Long courseId,
                               Long sessionId,
                               String question,
                               String answer,
                               List<SseReferenceVO> references,
                               long latencyMs);

    /**
     * 查询某会话下的问答明细列表（只返回本人会话）。
     *
     * @param sessionId 会话 ID
     * @param userId    当前登录用户 ID
     * @return 问答记录，按提问时间正序（前端从上往下渲染对话）
     */
    List<QaRecord> listBySession(Long sessionId, Long userId);

    /**
     * 教师端分页查询学生提问明细（只读）。
     *
     * @param courseId 课程 ID
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数，上限 100
     * @param keyword  关键词，匹配 question 或 answer，可为空
     * @return 分页结果，按提问时间倒序
     */
    IPage<QaRecord> pageRecords(Long courseId, Integer pageNum, Integer pageSize, String keyword);

    /**
     * 更新点赞 / 点踩状态。
     *
     * @param recordId 问答记录 ID
     * @param status   1-点赞, -1-点踩，其余值抛参数错误
     * @param userId   当前登录用户 ID，只能评价自己的提问
     */
    void updateFeedback(Long recordId, Integer status, Long userId);
}
