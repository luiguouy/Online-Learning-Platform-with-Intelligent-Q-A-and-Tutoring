import request from '@/utils/request';
import type { CourseDoc } from '@/types';

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
