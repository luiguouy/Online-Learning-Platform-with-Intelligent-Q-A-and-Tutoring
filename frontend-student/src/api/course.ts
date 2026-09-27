import request from '@/utils/request';
import type { Course } from '@/types';

/**
 * 课程列表
 * 接口：GET /api/course/list（成员 B 提供）
 * 返回：Result<Course[]>，**裸数组**（不是 IPage，没有 records/total）；
 *       学生视角返回全部课程（本期无选课关系表，B 回复确认单 Q12）。
 * 字段名：courseName（不是 name / course_title）。
 * 接口无请求参数。
 *
 * C2.4：Mock 分支已按 README 第八节清理清单删除，本函数只打真实后端。
 * 注：B 回复确认单附一第 5 条提到 CourseDocument 会带出 filePath，前端一律忽略、不展示。
 */
export async function listCourses(): Promise<Course[]> {
  return request.get<unknown, Course[]>('/course/list');
}
