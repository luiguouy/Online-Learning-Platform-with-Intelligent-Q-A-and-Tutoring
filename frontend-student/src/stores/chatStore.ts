import { ref } from 'vue';
import { defineStore } from 'pinia';

import { listRecords, listSessions } from '@/api/qa';
import type { ChatMessage, QaRecord, QaSession } from '@/types';

/**
 * 会话与消息
 *
 * Week 1（C1.5）范围：课程切换 → 拉历史会话列表 → 切会话看历史问答，数据来自 Mock。
 * 【不在本周范围】SSE 打字机流式（C2.1）、Markdown 渲染（C2.2）、出处抽屉（C2.3）、
 * 点赞点踩（C2.6）——本 store 只保留承载它们的字段位（references / recordId / streaming），
 * 不提前实现交互。
 */
export const useChatStore = defineStore('chat', () => {
  const sessions = ref<QaSession[]>([]);
  const currentSessionId = ref<number>(0);
  const messages = ref<ChatMessage[]>([]);
  const loadingSessions = ref(false);
  const loadingRecords = ref(false);

  /** 把后端 qa_record 映射为前端展示用的「提问 + 回答」两条消息 */
  function toMessages(records: QaRecord[]): ChatMessage[] {
    const list: ChatMessage[] = [];
    records.forEach((record) => {
      list.push({
        id: `q-${record.id}`,
        role: 'user',
        content: record.question,
      });
      list.push({
        id: `a-${record.id}`,
        role: 'ai',
        content: record.answer,
        // 后端返回 JSON 数组，历史数据可能为 null → 统一兜底成 []
        references: record.groundingReferences ?? [],
        recordId: record.id,
        streaming: false,
      });
    });
    return list;
  }

  /** 拉取某课程下的历史会话（课程切换后调用） */
  async function loadSessions(courseId: number): Promise<void> {
    loadingSessions.value = true;
    try {
      sessions.value = await listSessions(courseId);
      // 默认选中最近一条会话（列表按 createdAt 倒序，最新在最上）
      const first = sessions.value[0];
      if (first) {
        await selectSession(first.id);
      } else {
        currentSessionId.value = 0;
        messages.value = [];
      }
    } finally {
      loadingSessions.value = false;
    }
  }

  /** 切换会话并加载其历史问答 */
  async function selectSession(sessionId: number): Promise<void> {
    currentSessionId.value = sessionId;
    loadingRecords.value = true;
    try {
      messages.value = toMessages(await listRecords(sessionId));
    } finally {
      loadingRecords.value = false;
    }
  }

  /** 清空当前展示（新建会话 / 退出登录时调用） */
  function clear(): void {
    sessions.value = [];
    currentSessionId.value = 0;
    messages.value = [];
  }

  return {
    sessions,
    currentSessionId,
    messages,
    loadingSessions,
    loadingRecords,
    loadSessions,
    selectSession,
    clear,
  };
});
