import request from '@/utils/request';
import type { Course } from '@/types';

/**
 * 课程列表（教师端只读，仅用于切换当前课程）
 * 接口：GET /api/course/list（成员 B 提供，无请求参数）
 *
 * Q12 已确认：字段名是 `courseName`；教师登录后后端只返回**自己任课**的课程
 * （学生才是全部课程），所以教师端 courseId 天然满足后端的越权校验。
 */
export async function fetchCourseList(): Promise<Course[]> {
  return request.get<unknown, Course[]>('/course/list');
}
