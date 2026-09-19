import request from '@/utils/request';
import type { CourseDoc, PageResult, QaRecord } from '@/types';

/**
 * 教师端课件接口（成员 B 提供，D 为唯一调用方）
 * 路径与 DEV_SPECIFICATION.md / TEAM_WORK_DIVISION.md 接口矩阵逐字符一致，禁止改写。
 *
 * D2.1（2026-09-17）：解除 Mock，接口结构按 B 的答复定稿
 * （dev-docs/mock/B回复-接口确认单(Q7-Q13).md）。
 */

/**
 * 课件列表：GET /api/teacher/docs/list?courseId=
 *
 * Q8 已确认返回**裸数组** `List<CourseDocument>`（不是 IPage，没有 records/total），
 * 经响应拦截器解包后即为 CourseDoc[]，调用方无需再取 `.records`，摘除原 TODO(Q8)。
 * 后端按 createdAt 倒序返回；`errorMsg` 在非 FAILED 状态为**空串**（不是 null）。
 */
export async function fetchDocList(courseId: number): Promise<CourseDoc[]> {
  return request.get<unknown, CourseDoc[]>('/teacher/docs/list', { params: { courseId } });
}

/** 课件删除：DELETE /api/teacher/docs/{id}（后端级联清除该课件在 Chroma 中的向量切片） */
export async function deleteDoc(id: number): Promise<void> {
  await request.delete<unknown, void>(`/teacher/docs/${id}`);
}

/**
 * 重建课件索引：POST /api/teacher/docs/{id}/reindex
 *
 * 后端行为（TeacherDocumentController#reindex）：先清旧向量 → 状态置回 `PARSING`
 * → **异步**重新切块，故返回的 `true` 只代表"任务已受理"。
 * 前端须靠课件列表的 3 秒轮询捕捉 `PARSING → CHUNKED / FAILED`（D2.2 / D2.4）。
 */
export async function reindexDoc(id: number): Promise<boolean> {
  return request.post<unknown, boolean>(`/teacher/docs/${id}/reindex`);
}

/**
 * 问答记录分页查询：GET /api/teacher/qa/records（教师端只读，D2.3）
 *
 * ⚠️ 返回 MyBatis-Plus 的 **IPage**（`records` / `total` / `size` / `current` / `pages`），
 * 与 `fetchDocList` 的**裸数组**不同 —— 2026-09-19 实测确认，勿套同一套解包逻辑。
 * 后端会校验教师是否任课该课程（越权返回业务异常）。
 */
export async function fetchQaRecords(params: {
  courseId: number;
  pageNum: number;
  pageSize: number;
  keyword?: string;
}): Promise<PageResult<QaRecord>> {
  const query: Record<string, unknown> = {
    courseId: params.courseId,
    pageNum: params.pageNum,
    pageSize: params.pageSize,
  };
  // 关键词为空时不传该参数，避免后端把空串当作 like '%%' 的额外条件
  if (params.keyword) {
    query.keyword = params.keyword;
  }
  return request.get<unknown, PageResult<QaRecord>>('/teacher/qa/records', { params: query });
}
