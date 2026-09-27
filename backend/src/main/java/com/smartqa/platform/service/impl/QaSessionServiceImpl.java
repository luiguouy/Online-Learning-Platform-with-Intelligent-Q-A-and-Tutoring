package com.smartqa.platform.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.entity.QaSession;
import com.smartqa.platform.mapper.QaSessionMapper;
import com.smartqa.platform.service.QaSessionService;
import com.smartqa.platform.vo.SessionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 问答会话服务实现。
 *
 * @author 成员 B
 */
@Slf4j
@Service
public class QaSessionServiceImpl extends ServiceImpl<QaSessionMapper, QaSession> implements QaSessionService {

    /** 会话标题取提问前 N 个字符（与前端展示宽度对齐） */
    private static final int TITLE_MAX_LENGTH = 15;

    private static final String DEFAULT_TITLE = "新建答疑会话";

    @Override
    public Long createSessionLazy(Long courseId, String question) {
        return createSessionLazyForUser(currentUserId(), courseId, question);
    }

    @Override
    public Long createSessionLazyForUser(Long userId, Long courseId, String question) {
        if (userId == null) {
            throw new BusinessException("提问学生ID不能为空");
        }
        if (courseId == null) {
            throw new BusinessException("课程ID不能为空");
        }
        QaSession session = QaSession.builder()
                .userId(userId)
                .courseId(courseId)
                .sessionTitle(buildTitle(question))
                .build();
        save(session);

        if (session.getId() == null) {
            // 理论上不会发生；真发生说明主键策略被改坏，必须立刻暴露而不是让 A 拿到 null
            throw new BusinessException(500, "会话创建失败：未取到主键，请检查 id-type 配置");
        }
        log.info("懒创建问答会话成功, sessionId={}, userId={}, courseId={}", session.getId(), userId, courseId);
        return session.getId();
    }

    @Override
    public List<SessionVO> listMySessions(Long courseId, Long userId) {
        if (courseId == null) {
            throw new BusinessException("课程ID不能为空");
        }
        if (userId == null) {
            throw new BusinessException(401, "未取到登录用户，无法查询会话");
        }
        List<QaSession> sessions = list(Wrappers.<QaSession>lambdaQuery()
                .eq(QaSession::getCourseId, courseId)
                .eq(QaSession::getUserId, userId)
                .orderByDesc(QaSession::getUpdatedAt));
        return sessions.stream()
                .map(s -> SessionVO.builder()
                        .id(s.getId())
                        .courseId(s.getCourseId())
                        .sessionTitle(s.getSessionTitle())
                        .createdAt(s.getCreatedAt())
                        .updatedAt(s.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public QaSession getOwnedSession(Long sessionId, Long userId) {
        if (sessionId == null) {
            throw new BusinessException("会话ID不能为空");
        }
        QaSession session = getById(sessionId);
        if (session == null) {
            throw new BusinessException(404, "会话不存在");
        }
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException(403, "无权查看他人会话");
        }
        return session;
    }

    @Override
    public void touch(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        update(Wrappers.<QaSession>lambdaUpdate()
                .eq(QaSession::getId, sessionId)
                .set(QaSession::getUpdatedAt, LocalDateTime.now()));
    }

    /** 标题取提问前 15 个字符；提问为空时回落到默认标题 */
    private String buildTitle(String question) {
        if (!StringUtils.hasText(question)) {
            return DEFAULT_TITLE;
        }
        String trimmed = question.trim();
        return trimmed.length() <= TITLE_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, TITLE_MAX_LENGTH);
    }

    /**
     * 取当前登录用户 ID。
     *
     * <p>Sa-Token 登录态存于 ThreadLocal，异步线程里取不到，
     * 因此这里抛的是带指引的 401，而不是让人摸不着头脑的 NPE。</p>
     */
    private Long currentUserId() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(401,
                    "未取到登录态，无法创建会话；异步线程请改用 createSessionLazyForUser(userId, ...)");
        }
        return StpUtil.getLoginIdAsLong();
    }
}
