import request from '@/utils/request';

/**
 * 知识点精解（C2.5）
 *
 * 接口：POST /api/knowledge/generate（成员 A 负责，A2.5）
 * 请求体：{ courseId, pointName }
 * 限流：与 /api/qa/chat/stream 共用「每用户每 60 秒 20 次」窗口
 *       （B 的 QaRateLimitInterceptor 把两条路径注册在一起，见 MEMBER_B_DEV_GUIDE 4.6）
 *
 * ⚠️ 响应字段待确认（截至交付时 Issue #6 仍 OPEN，远端 dev 上还没有 KnowledgeController）：
 * 文档口径是「返回结构化 Markdown 精解（核心概念定义 + 难点辨析）」
 * （MEMBER_A_DEV_GUIDE 第一章第 6 条 / THREE_WEEK_PLAN A2.5 / 本指南 5.1 第 3 条），
 * 据此主口径按 Result<String> 解析。
 *
 * 若 A 最终返回 VO 对象，这里额外兜一层常见 markdown 承载字段；两者都取不到就**明确抛错**。
 * 不静默返回空串：那会变成「接口通了但面板一片空白」——联调时最难定位的一类问题。
 */

/** 知识点精解请求体（字段名以 A 的 KnowledgeGenerateDTO 为准，此处按接口矩阵约定） */
export interface KnowledgeGenerateParams {
  courseId: number;
  pointName: string;
}

/** VO 形态的兜底字段名（按可能性排序）；A 确认响应结构后只保留命中的那一个 */
const MARKDOWN_FIELDS = ['markdown', 'content', 'explanation', 'detail'] as const;

/**
 * 触发知识点精解，返回可直接交给 MarkdownViewer 渲染的 Markdown 文本。
 *
 * @throws Error 响应结构不符合预期时抛出（由面板展示为错误态，不当作空内容渲染）
 */
export async function generateKnowledgePoint(params: KnowledgeGenerateParams): Promise<string> {
  const data = await request.post<unknown, unknown>('/knowledge/generate', params);

  if (typeof data === 'string' && data.trim()) {
    return data;
  }

  if (data && typeof data === 'object') {
    const record = data as Record<string, unknown>;
    for (const key of MARKDOWN_FIELDS) {
      const value = record[key];
      if (typeof value === 'string' && value.trim()) {
        return value;
      }
    }
  }

  throw new Error(
    '知识点解析接口返回结构不符合预期（既不是 Markdown 字符串，也不含 markdown 字段），请与成员 A 确认响应 VO 字段',
  );
}
