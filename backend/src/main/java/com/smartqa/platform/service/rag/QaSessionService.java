package com.smartqa.platform.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 问答会话管理服务（轻量占位实现）
 *
 * 职责：
 *   - 为每次问答分配一个 sessionId，用于追踪多轮对话上下文
 *   - 当前采用内存 ConcurrentHashMap 存储（MVP 阶段）
 *   - 后期由成员 B 对接数据库持久化后替换此实现
 *
 * 此类存在的核心价值：解耦 SseStreamService 对 sessionId 的依赖，
 * 避免业务逻辑直接散落在 SSE 流中。
 */
@Service
public class QaSessionService {

    private static final Logger log = LoggerFactory.getLogger(QaSessionService.class);

    /** 全局 sessionId 自增生成器（生产环境替换为雪花算法或数据库自增） */
    private final AtomicLong idGenerator = new AtomicLong(1000L);

    /**
     * sessionId -> 会话元信息（courseId 等）的内存索引
     * Key: sessionId, Value: 简易元信息 Map（courseId, createTime 等）
     */
    private final Map<Long, Map<String, Object>> sessionStore = new ConcurrentHashMap<>();

    /**
     * 懒创建问答会话：若 sessionId 为空则新建，否则直接返回已有 sessionId
     *
     * @param courseId 课程 ID（新建会话时绑定）
     * @param firstQuestion 首个问题（便于日志溯源）
     * @return 有效的 sessionId
     */
    public Long createSessionLazy(Long courseId, String firstQuestion) {
        Long newId = idGenerator.getAndIncrement();
        Map<String, Object> meta = new ConcurrentHashMap<>();
        meta.put("courseId", courseId);
        meta.put("firstQuestion", firstQuestion);
        meta.put("createTime", System.currentTimeMillis());
        sessionStore.put(newId, meta);

        log.info("[QaSession] 新建问答会话 sessionId={}, courseId={}", newId, courseId);
        return newId;
    }

    /**
     * 查询会话元信息（调试 / 审计用途）
     *
     * @param sessionId 会话 ID
     * @return 元信息 Map，不存在则返回 null
     */
    public Map<String, Object> getSessionMeta(Long sessionId) {
        return sessionStore.get(sessionId);
    }

    /**
     * 判断会话是否存在
     *
     * @param sessionId 会话 ID
     * @return true 表示有效会话
     */
    public boolean exists(Long sessionId) {
        return sessionStore.containsKey(sessionId);
    }
}
