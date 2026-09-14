import request from '@/utils/request';
import { USE_MOCK } from '@/config';
import { mockListCourses } from '@/mock/courses';
import type { Course } from '@/types';

/**
 * 课程列表
 * 接口：GET /api/course/list（成员 B 提供）
 * 返回：Result<Course[]>，**裸数组**（不是 IPage，没有 records/total）；
 *       学生视角返回全部课程（本期无选课关系表，B 回复确认单 Q12）。
 * 字段名：courseName（不是 name / course_title）。
 * 接口无请求参数。
 */
export async function listCourses(): Promise<Course[]> {
  if (USE_MOCK) {
    return mockListCourses();
  }
  return request.get<unknown, Course[]>('/course/list');
}
