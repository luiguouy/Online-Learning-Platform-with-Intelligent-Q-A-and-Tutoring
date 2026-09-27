package com.smartqa.platform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartqa.platform.entity.QaSession;
import com.smartqa.platform.vo.SessionVO;

import java.util.List;

/**
 * 问答会话服务。
 *
 * @author 成员 B
 */
public interface QaSessionService extends IService<QaSession> {

    /**
     * 【冻结契约 · Day 3 死线】懒创建会话。
     *
     * <p>成员 A 在 SSE 接口收到 {@code sessionId=0} 时调用本方法，
     * 拿到新建 sessionId 后写进 {@code event: done} 数据包回传前端。</p>
     *
     * <p>⚠️ 返回值不能为 null（A 用它替换 done 包里的 sessionId）。
     * ⚠️ 内部需要登录态，必须在请求线程调用，不要在 sseExecutor 异步线程里调。</p>
     *
     * @param courseId 课程 ID
     * @param question 学生原始提问，标题取其前 15 个字符
     * @return 新建的会话 ID
     */
    Long createSessionLazy(Long courseId, String question);

    /**
     * 懒创建会话的显式用户版本。
     *
     * <p>异步线程（sseExecutor）里取不到 Sa-Token 登录态，
     * A 若有异步场景请改用本重载。</p>
     *
     * @param userId   提问学生 ID
     * @param courseId 课程 ID
     * @param question 学生原始提问
     * @return 新建的会话 ID
     */
    Long createSessionLazyForUser(Long userId, Long courseId, String question);

    /**
     * 查询当前学生在某课程下的会话列表。
     *
     * <p>只返回自己的会话，防止学生 A 看到学生 B 的提问历史。</p>
     *
     * @param courseId 课程 ID
     * @param userId   当前登录用户 ID
     * @return 会话列表，按最后活跃时间倒序
     */
    List<SessionVO> listMySessions(Long courseId, Long userId);

    /**
     * 取会话并校验归属，非本人会话抛 403。
     *
     * @param sessionId 会话 ID
     * @param userId    当前登录用户 ID
     * @return 会话实体
     */
    QaSession getOwnedSession(Long sessionId, Long userId);

    /**
     * 刷新会话的最后活跃时间，让历史会话列表按最近提问排序。
     *
     * @param sessionId 会话 ID
     */
    void touch(Long sessionId);
}
