/**
 * 全局类型声明
 * 字段名严格来自项目文档，禁止臆造：
 * - 状态机四态见 DEV_SPECIFICATION.md 4.2（命名冻结，禁止别名）
 * - 角色取值见 AGENT_INSTRUCTIONS.md 3.1 种子数据（TEACHER / STUDENT）
 * - 课件列表 / 登录字段已按成员 B 的答复定稿，见
 *   dev-docs/mock/B回复-接口确认单(Q7-Q13).md（2026-09-13，附源码行号证据）
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

/**
 * 课件文档行（对应后端 CourseDocument）
 *
 * ⚠️ 后端序列化还会带出 `filePath`（服务器磁盘绝对路径）与 `isDeleted`（逻辑删除标记，恒 0）。
 * 二者**故意不在此声明**：`filePath` 属信息暴露点、禁止展示（Q8 第 5 条）；`isDeleted` 不作过滤依据。
 */
export interface CourseDoc {
  id: number;
  courseId?: number;
  fileName: string;
  fileType: string;
  fileSize?: number;
  chunkCount: number;
  parseStatus: ParseStatus;
  /** 切块失败原因，仅 FAILED 时有值；其余状态后端返回**空串**（不是 null），最长 500 字符 */
  errorMsg?: string;
  createdAt: string;
  updatedAt?: string;
}

/**
 * 登录返回（对应后端 LoginVO）
 * D2.1（2026-09-17）定稿：Q7 已确认字段名与类型，摘除原 TODO(Q7)。
 * `avatarUrl` 种子数据为空串，渲染前判空。
 */
export interface LoginResult {
  token: string;
  role: UserRole;
  userId?: number;
  username?: string;
  nickname?: string;
  avatarUrl?: string;
}

/** SSE 参考资料出处（Week 2 问答记录页使用） */
export interface SseReference {
  docId: number;
  fileName: string;
  chunkIndex: number;
  score: number;
  snippet: string;
}
