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

/**
 * 分页结果（MyBatis-Plus IPage 序列化结构）
 *
 * 2026-09-19 实测确认：`/api/teacher/qa/records` 的 `data` keys 恰为这五个
 * （字段名是 `current` 而**不是** `pageNum`）。
 * ⚠️ 与 `/api/teacher/docs/list` 的**裸数组**结构不同，两个接口不能套同一套解包逻辑。
 */
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/**
 * 问答记录行（对应后端 `QaRecord` 实体，无 VO 中转）
 *
 * ⚠️ 后端序列化还会带出 `isDeleted`（逻辑删除标记，恒 0），故意不声明、不展示。
 * `groundingReferences` 实测为**数组**（后端 JacksonTypeHandler 已反序列化，
 * 与成员 A 的 SSE references 事件共用 `SseReferenceVO` 契约）；保留 string 分支
 * 仅作兼容保护，正常不会命中。
 * `answer` 是 **Markdown 文本**（含 `###`/`**`/列表符号），列表展示前需转纯文本摘要。
 */
export interface QaRecord {
  id: number;
  sessionId?: number;
  userId?: number;
  courseId?: number;
  question: string;
  answer: string;
  groundingReferences?: SseReference[] | string | null;
  /** 学生打分：1-点赞，-1-点踩，0-未评（后端常量 FEEDBACK_UP / FEEDBACK_DOWN / FEEDBACK_NONE） */
  feedbackRating?: number;
  /** 模型生成耗时（毫秒） */
  latencyMs?: number;
  createdAt: string;
}
