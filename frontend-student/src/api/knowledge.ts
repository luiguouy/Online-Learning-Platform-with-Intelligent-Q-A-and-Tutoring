import request from '@/utils/request';

/**
 * 知识点精解（C2.5）
 *
 * 接口：POST /api/knowledge/generate（成员 A，A2.5，已随 PR #44 合入 dev）
 * 契约以 A 的源码为准，不臆造：
 * - 请求体 KnowledgeGenerateDTO：{ courseId(Long, @NotNull), knowledgePoint(String, @NotBlank, @Size(max=100)) }
 * - 响应   Result<KnowledgeGenerateVO>：解包 data 后为 { courseId, knowledgePoint, content }
 * - content 是结构化 Markdown 精解（核心概念定义 + 难点辨析），本期不含自测题
 * - 鉴权：需登录（Controller 内 StpUtil.checkLogin）；限流与 /api/qa/chat/stream 同一窗口
 *
 * 服务端把 knowledgePoint 直接 embed 后做 Top-K 向量检索（见 KnowledgeService#generate），
 * 所以它语义上是「一个短话题短语」，而不是整段提问 —— 这也解释了 DTO 为何限制 100 字。
 */

/** 知识点名称长度上限（对齐 KnowledgeGenerateDTO 的 @Size(max = 100)，超了后端回 400） */
const KNOWLEDGE_POINT_MAX_LENGTH = 100;

/** 知识点精解请求体（字段名逐字对齐 KnowledgeGenerateDTO） */
export interface KnowledgeGenerateParams {
  courseId: number;
  /** 知识点名称（短话题短语），≤100 字 */
  knowledgePoint: string;
}

/** 知识点解析响应 VO（KnowledgeGenerateVO） */
interface KnowledgeGenerateVO {
  courseId?: number;
  knowledgePoint?: string;
  /** 结构化 Markdown 精解 */
  content?: string;
}

/**
 * 触发知识点精解，返回可直接交给 MarkdownViewer 渲染的 Markdown 文本。
 *
 * @throws Error 入参不满足后端约束，或响应结构不符合契约时抛出
 *         （由 KnowledgePanel 展示为错误态，不当作空内容渲染 —— 避免"接口通了但面板一片空白"）
 */
export async function generateKnowledgePoint(
  params: KnowledgeGenerateParams,
): Promise<string> {
  const knowledgePoint = params.knowledgePoint.trim();

  // 端上先挡，别把 400 原样抛给用户：后端只会回一句"知识点名称过长"，
  // 用户无从知道超了多少、更不知道该怎么办。
  if (!knowledgePoint) {
    throw new Error('知识点名称为空，无法生成精解');
  }
  if (knowledgePoint.length > KNOWLEDGE_POINT_MAX_LENGTH) {
    throw new Error(
      `知识点名称最多 ${KNOWLEDGE_POINT_MAX_LENGTH} 字（当前 ${knowledgePoint.length} 字）。` +
        '知识点精解按「一个短话题」检索课件，请用更短的一句话（如「页面置换算法 LRU 与 FIFO 对比」）后再试。',
    );
  }

  const data = await request.post<unknown, KnowledgeGenerateVO>(
    '/knowledge/generate',
    {
      courseId: params.courseId,
      knowledgePoint,
    },
    {
      // 服务端阻塞式生成最长约 60s（rag.llm.timeout-seconds）：request.ts 全局 20s 会提前
      // abort（后端还在烧 Token 而前端已报失败），仅此请求放宽；不动全局，避免拖慢其他接口故障感知
      timeout: 90_000,
    },
  );

  const content = data?.content;
  if (typeof content === 'string' && content.trim()) {
    return content;
  }

  throw new Error(
    '知识点解析接口未返回 content 字段（期望 Result<KnowledgeGenerateVO>），请与成员 A 确认响应结构',
  );
}
