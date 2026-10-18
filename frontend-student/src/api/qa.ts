import request from '@/utils/request';
import type { AttachmentInfo, QaRecord, QaSession } from '@/types';

/**
 * 学生端问答记录接口（支持历史会话、删除、归档与附件上传）
 */

/**
 * 历史会话列表（只返回当前学生自己的会话）
 * 接口：GET /api/qa/sessions?courseId=&isArchived=
 */
export async function listSessions(courseId: number, isArchived?: number): Promise<QaSession[]> {
  return request.get<unknown, QaSession[]>('/qa/sessions', {
    params: { courseId, ...(isArchived !== undefined ? { isArchived } : {}) },
  });
}

/**
 * 删除会话
 * 接口：DELETE /api/qa/sessions/{id}
 */
export async function deleteSession(sessionId: number): Promise<boolean> {
  return request.delete<unknown, boolean>(`/qa/sessions/${sessionId}`);
}

/**
 * 切换会话归档状态
 * 接口：PUT /api/qa/sessions/{id}/archive
 */
export async function toggleArchiveSession(sessionId: number): Promise<number> {
  return request.put<unknown, number>(`/qa/sessions/${sessionId}/archive`);
}

/**
 * 上传提问附件（图片/参考文档）
 * 接口：POST /api/qa/upload/attachment
 */
export async function uploadAttachment(file: File): Promise<AttachmentInfo> {
  const formData = new FormData();
  formData.append('file', file);
  return request.post<unknown, AttachmentInfo>('/qa/upload/attachment', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

/**
 * 指定会话下的问答记录（后端按提问时间正序返回，前端直接从上往下渲染对话）
 * 接口：GET /api/qa/records?sessionId=
 */
export async function listRecords(sessionId: number): Promise<QaRecord[]> {
  return request.get<unknown, QaRecord[]>('/qa/records', { params: { sessionId } });
}

/**
 * 点赞 / 点踩（C2.6）
 * 接口：POST /api/qa/records/{id}/feedback
 */
export async function submitFeedback(recordId: number, status: 1 | -1): Promise<boolean> {
  return request.post<unknown, boolean>(`/qa/records/${recordId}/feedback`, { status });
}

