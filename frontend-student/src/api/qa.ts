import request from '@/utils/request';
import { USE_MOCK } from '@/config';
import { mockListRecords, mockListSessions } from '@/mock/qa';
import type { QaRecord, QaSession } from '@/types';

/**
 * 历史会话列表
 * 接口：GET /api/qa/sessions?courseId=（成员 B 提供，B2.4 截止 Day 11）
 * 返回：Result<QaSession[]>
 */
export async function listSessions(courseId: number): Promise<QaSession[]> {
  if (USE_MOCK) {
    return mockListSessions(courseId);
  }
  return request.get<unknown, QaSession[]>('/qa/sessions', { params: { courseId } });
}

/**
 * 指定会话下的问答记录
 * 接口：GET /api/qa/records?sessionId=（成员 B 提供，B2.4 截止 Day 11）
 * 返回：Result<QaRecord[]>
 * 注意：groundingReferences 后端为 JSON 数组（B 回复确认单 Q10），且历史数据可能为 null，
 *       页面侧统一用 `?? []` 兜底（见 ChatWorkspace.vue）。
 */
export async function listRecords(sessionId: number): Promise<QaRecord[]> {
  if (USE_MOCK) {
    return mockListRecords(sessionId);
  }
  return request.get<unknown, QaRecord[]>('/qa/records', { params: { sessionId } });
}
