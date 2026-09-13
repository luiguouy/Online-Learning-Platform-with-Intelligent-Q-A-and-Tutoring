package com.smartqa.platform.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.entity.QaRecord;
import com.smartqa.platform.entity.QaSession;
import com.smartqa.platform.mapper.QaRecordMapper;
import com.smartqa.platform.service.QaRecordService;
import com.smartqa.platform.service.QaSessionService;
import com.smartqa.platform.vo.SseReferenceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 问答记录服务实现。
 *
 * @author 成员 B
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaRecordServiceImpl extends ServiceImpl<QaRecordMapper, QaRecord> implements QaRecordService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final QaSessionService qaSessionService;

    @Override
    public Long saveStreamingRecord(Long courseId,
                                    Long sessionId,
                                    String question,
                                    String answer,
                                    List<SseReferenceVO> references,
                                    long latencyMs) {
        // 本方法设计为在请求线程调用（Sa-Token 登录态存于 ThreadLocal）。
        // 若 A 在 sseExecutor 异步线程里调用，isLogin() 会返回 false，
        // 此时会抛出带明确指引的 401，而不是难以定位的 NullPointerException。
        if (!StpUtil.isLogin()) {
            throw new BusinessException(401,
                    "未取到登录态，无法保存问答记录；异步线程请改用 saveStreamingRecordAs(userId, ...)");
        }
        return doSave(StpUtil.getLoginIdAsLong(), courseId, sessionId, question, answer, references, latencyMs);
    }

    @Override
    public Long saveStreamingRecordAs(Long userId,
                                      Long courseId,
                                      Long sessionId,
                                      String question,
                                      String answer,
                                      List<SseReferenceVO> references,
                                      long latencyMs) {
        if (userId == null) {
            throw new BusinessException("提问学生ID不能为空");
        }
        return doSave(userId, courseId, sessionId, question, answer, references, latencyMs);
    }

    @Override
    public List<QaRecord> listBySession(Long sessionId, Long userId) {
        QaSession session = qaSessionService.getOwnedSession(sessionId, userId);
        return list(Wrappers.<QaRecord>lambdaQuery()
                .eq(QaRecord::getSessionId, session.getId())
                .orderByAsc(QaRecord::getCreatedAt));
    }

    @Override
    public IPage<QaRecord> pageRecords(Long courseId, Integer pageNum, Integer pageSize, String keyword) {
        if (courseId == null) {
            throw new BusinessException("课程ID不能为空");
        }
        Page<QaRecord> page = new Page<>(normalizePageNum(pageNum), normalizePageSize(pageSize));

        LambdaQueryWrapper<QaRecord> qw = Wrappers.<QaRecord>lambdaQuery()
                .eq(QaRecord::getCourseId, courseId)
                .orderByDesc(QaRecord::getCreatedAt);

        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            // 关键词匹配提问或回答，整体加括号，避免 or 破坏前面的 courseId 条件
            qw.and(w -> w.like(QaRecord::getQuestion, kw).or().like(QaRecord::getAnswer, kw));
        }
        return page(page, qw);
    }

    @Override
    public void updateFeedback(Long recordId, Integer status, Long userId) {
        if (recordId == null) {
            throw new BusinessException("记录ID不能为空");
        }
        if (status == null || !(status == 1 || status == -1)) {
            throw new BusinessException("反馈状态只能是 1（点赞）或 -1（点踩）");
        }
        QaRecord record = getById(recordId);
        if (record == null) {
            throw new BusinessException(404, "问答记录不存在");
        }
        if (!Objects.equals(record.getUserId(), userId)) {
            throw new BusinessException(403, "只能评价自己的提问");
        }
        update(Wrappers.<QaRecord>lambdaUpdate()
                .eq(QaRecord::getId, recordId)
                .set(QaRecord::getFeedbackRating, status));
    }

    private Long doSave(Long userId,
                        Long courseId,
                        Long sessionId,
                        String question,
                        String answer,
                        List<SseReferenceVO> references,
                        long latencyMs) {
        if (courseId == null) {
            throw new BusinessException("课程ID不能为空");
        }
        if (!StringUtils.hasText(question)) {
            throw new BusinessException("提问内容不能为空");
        }

        Long realSessionId = sessionId;
        if (realSessionId == null || realSessionId <= 0) {
            // 兜底：正常流程下 A 已先调 createSessionLazy 并把真实 sessionId 传进来。
            // 这里再兜一层，避免整条链路因为一个 0 而丢记录。
            realSessionId = qaSessionService.createSessionLazyForUser(userId, courseId, question);
            log.warn("saveStreamingRecord 收到无效 sessionId={}，已兜底懒创建为 {}", sessionId, realSessionId);
        }

        QaRecord record = QaRecord.builder()
                .sessionId(realSessionId)
                .userId(userId)
                .courseId(courseId)
                .question(question)
                .answer(answer == null ? "" : answer)
                .groundingReferences(references == null ? Collections.emptyList() : references)
                .feedbackRating(QaRecord.FEEDBACK_NONE)
                .latencyMs((int) Math.min(Math.max(latencyMs, 0L), Integer.MAX_VALUE))
                .build();
        save(record);

        qaSessionService.touch(realSessionId);
        return record.getId();
    }

    private long normalizePageNum(Integer pageNum) {
        return (pageNum == null || pageNum < 1) ? 1L : pageNum.longValue();
    }

    private long normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
