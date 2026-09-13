/**
 * 全局类型声明（学生端）
 * 字段名严格来自项目文档，禁止臆造：
 * - 角色取值见 AGENT_INSTRUCTIONS.md 3.1 种子数据（TEACHER / STUDENT）
 * - 登录返回见 dev-docs/mock/B回复-接口确认单(Q7-Q13).md「Q7」（已按真实源码核对）
 * - 课程列表见同上「Q12」：裸数组，字段 courseName
 * - SSEReference 五字段见 DEV_SPECIFICATION.md 4.2（与后端 SseReferenceVO 冻结一致）
 * - SSE 四事件载荷见 DEV_SPECIFICATION.md 4.2
 */

/** 用户角色 */
export type UserRole = 'TEACHER' | 'STUDENT';

/** 课程（学生端只读，用于切换当前检索范围） */
export interface Course {
  id: number;
  courseName: string;
  courseCode?: string;
  teacherId?: number;
  description?: string;
  /** 种子数据为空串，渲染前判空 */
  coverImage?: string;
}

/**
 * 登录返回（POST /api/auth/login）
 * 解包 Result 后取 data；B 侧已确认 6 字段，学生端实际用前四个。
 */
export interface LoginResult {
  token: string;
  role: UserRole;
  nickname?: string;
  userId?: number;
  username?: string;
  /** 种子数据为空串，渲染前判空 */
  avatarUrl?: string;
}

/** SSE 参考资料出处（五字段冻结，DEV_SPECIFICATION.md 4.2） */
export interface SseReference {
  docId: number;
  fileName: string;
  chunkIndex: number;
  score: number;
  snippet: string;
}

/** SSE `done` 事件载荷（DEV_SPECIFICATION.md 4.2；recordId 必带回，供点赞/点踩使用） */
export interface SseDonePayload {
  recordId: number;
  sessionId: number;
  finishReason?: string;
  totalTokens?: number;
}

/** SSE `error` 事件载荷 */
export interface SseErrorPayload {
  errorCode: number;
  message: string;
}

/** 会话（GET /api/qa/sessions?courseId=） */
export interface QaSession {
  id: number;
  title: string;
  createdAt: string;
}

/** 问答记录（GET /api/qa/records?sessionId=） */
export interface QaRecord {
  id: number;
  question: string;
  answer: string;
  /** 后端为 JSON 数组；历史数据可能为 null，渲染前统一兜底成 [] */
  groundingReferences?: SseReference[] | null;
  /** 点赞 1 / 点踩 -1 / 未评价 null */
  feedbackRating?: number | null;
  createdAt?: string;
}

/** 前端消息模型（仅前端展示用，非接口字段） */
export interface ChatMessage {
  id: string;
  role: 'user' | 'ai';
  content: string;
  /** AI 消息：命中的课件出处 */
  references?: SseReference[];
  /** AI 消息：落库后回传的 recordId（点赞/点踩用） */
  recordId?: number;
  /** AI 消息：是否仍在流式输出中 */
  streaming?: boolean;
}
