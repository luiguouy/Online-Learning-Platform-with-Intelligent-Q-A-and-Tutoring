/**
 * 全局类型声明
 * 字段名严格来自项目文档，禁止臆造：
 * - 状态机四态见 DEV_SPECIFICATION.md 4.2（命名冻结，禁止别名）
 * - 角色取值见 AGENT_INSTRUCTIONS.md 3.1 种子数据（TEACHER / STUDENT）
 * - 课件列表字段见 MEMBER_D_DEV_GUIDE.md 4.1
 * - 参考资料字段见 DEV_SPECIFICATION.md 4.2（与后端 SseReferenceVO 一致）
 */

/** 课件解析状态机（四态冻结） */
export type ParseStatus = 'PENDING' | 'PARSING' | 'CHUNKED' | 'FAILED';

/** 用户角色 */
export type UserRole = 'TEACHER' | 'STUDENT';

/** 课程（教师端只读，仅用于切换当前课程） */
export interface Course {
  id: number;
  courseName: string;
  courseCode?: string;
  teacherId?: number;
  description?: string;
}

/** 课件文档行 */
export interface CourseDoc {
  id: number;
  fileName: string;
  fileType: string;
  chunkCount: number;
  parseStatus: ParseStatus;
  createdAt: string;
  /** 切块失败时的原因，仅 FAILED 状态有值 */
  errorMsg?: string;
}

/** 登录返回（token / role 字段名待成员 B 的 Knife4j 文档最终确认） */
export interface LoginResult {
  token: string;
  role: UserRole;
  nickname?: string;
  userId?: number;
}

/** SSE 参考资料出处（Week 2 问答记录页使用） */
export interface SseReference {
  docId: number;
  fileName: string;
  chunkIndex: number;
  score: number;
  snippet: string;
}
