import request from '@/utils/request';
import { USE_MOCK } from '@/config';
import { mockCourses } from '@/mock/teacherDocs';
import type { Course } from '@/types';

/**
 * 课程列表（教师端只读，仅用于切换当前课程）
 * 接口：GET /api/course/list（成员 B 提供）
 */
export async function fetchCourseList(): Promise<Course[]> {
  if (USE_MOCK) {
    return JSON.parse(JSON.stringify(mockCourses)) as Course[];
  }
  return request.get<unknown, Course[]>('/course/list');
}
