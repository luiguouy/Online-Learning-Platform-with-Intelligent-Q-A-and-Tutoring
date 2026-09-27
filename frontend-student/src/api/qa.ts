import request from '@/utils/request';
import type { QaRecord, QaSession } from '@/types';

/**
 * 学生端问答记录接口（成员 B 的 QaSessionController，路径逐字符冻结）
 *
 * C2.4：Mock 分支已按 README 第八节清理清单删除，本文件只打真实后端。
 * C2.6：新增 submitFeedback（点赞 / 点踩）。
 */

/**
 * 历史会话列表（只返回当前学生自己的会话）
 * 接口：GET /api/qa/sessions?courseId=
 * 返回：Result<QaSession[]>；标题字段固定 sessionTitle（SessionVO.java:33，无别名）
 */
export async function listSessions(courseId: number): Promise<QaSession[]> {
  return request.get<unknown, QaSession[]>('/qa/sessions', { params: { courseId } });
}

/**
 * 指定会话下的问答记录（后端按提问时间正序返回，前端直接从上往下渲染对话）
 * 接口：GET /api/qa/records?sessionId=
 * 返回：Result<QaRecord[]>
 * 注意：groundingReferences 是 qa_record 的 JSON 列快照，历史数据可能为 null；
 *       feedbackRating 后端用 0 表示未评价（QaRecord.FEEDBACK_NONE），前端统一兜底。
 */
export async function listRecords(sessionId: number): Promise<QaRecord[]> {
  return request.get<unknown, QaRecord[]>('/qa/records', { params: { sessionId } });
}

/**
 * 点赞 / 点踩（C2.6）
 * 接口：POST /api/qa/records/{id}/feedback
 * 请求体：{ status: 1 | -1 }（FeedbackDTO 字段名固定 status，取值只能是 1 或 -1）
 * 返回：Result<Boolean>，成功即 true；非法 status 由后端抛 400 参数错误
 */
export async function submitFeedback(recordId: number, status: 1 | -1): Promise<boolean> {
  return request.post<unknown, boolean>(`/qa/records/${recordId}/feedback`, { status });
}
