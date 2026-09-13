import request from '@/utils/request';
import { USE_MOCK } from '@/config';
import { mockListDocs, mockRemoveDoc } from '@/mock/teacherDocs';
import type { CourseDoc } from '@/types';

/**
 * 教师端课件接口（成员 B 提供，D 为唯一调用方）
 * 路径与 DEV_SPECIFICATION.md / TEAM_WORK_DIVISION.md 接口矩阵逐字符一致，禁止改写。
 */

/** 课件列表：GET /api/teacher/docs/list?courseId= */
export async function fetchDocList(courseId: number): Promise<CourseDoc[]> {
  if (USE_MOCK) {
    return mockListDocs(courseId);
  }
  return request.get<unknown, CourseDoc[]>('/teacher/docs/list', { params: { courseId } });
}

/** 课件删除：DELETE /api/teacher/docs/{id}（后端级联清除该课件在 Chroma 中的向量切片） */
export async function deleteDoc(id: number): Promise<void> {
  if (USE_MOCK) {
    mockRemoveDoc(id);
    return;
  }
  await request.delete<unknown, void>(`/teacher/docs/${id}`);
}
