import { ref } from 'vue';
import { defineStore } from 'pinia';

import { listRecords, listSessions, submitFeedback } from '@/api/qa';
import { SseChatClient } from '@/utils/sseClient';
import { TypewriterBuffer } from '@/utils/typewriter';
import type { ChatMessage, QaRecord, QaSession, SseReference } from '@/types';

/**
 * 会话与消息
 *
 * C1.5：课程切换 → 拉历史会话 → 切会话看历史问答。
 * C2.1：接入真实 SSE，流式回答以「打字机」方式上屏（节流在 TypewriterBuffer 里）。
 * C2.2 / C2.3：AI 回答的 Markdown 渲染与出处抽屉在 ChatWorkspace + 组件里落地，
 *              本 store 只负责把 references / recordId 挂到消息上。
 * C2.6：点赞 / 点踩（setFeedback），反馈态随历史记录一起回填。
 */
export const useChatStore = defineStore('chat', () => {
  const sessions = ref<QaSession[]>([]);
  const currentSessionId = ref<number>(0);
  const messages = ref<ChatMessage[]>([]);
  const loadingSessions = ref(false);
  const loadingRecords = ref(false);
  /** 历史问答加载失败原因（C3.1）；空串表示没有失败 */
  const recordsError = ref<string>('');
  /** 是否有一次流式回答正在生成（驱动输入框禁用与「停止生成」按钮） */
  const streaming = ref(false);

  /**
   * SSE 客户端：整个 store 共用一个实例。
   * sseClient 内部持有 AbortController，重复 startStream 会先中断上一次，
   * 因此不存在「两条流同时往一个气泡里写」的情况。
   */
  const sseClient = new SseChatClient();

  /**
   * 当前这次流式回答的打字机缓冲。
   * 用局部变量而非 ref：它不参与渲染，只负责把 delta 合并成每帧一次落地。
   */
  let activeBuffer: TypewriterBuffer | null = null;

  /**
   * 异步加载代次令牌（stale-guard）：快速切课/连点会话时，先发请求的慢响应
   * 会后到并覆盖新状态。写 state 前比对令牌，过期响应直接丢弃。
   * 两个计数器分开：会话列表加载与会话明细加载互不干扰，
   * 否则 loadSessions 内联 await selectSession 会误伤自己的令牌。
   * （同教师端 ba08487 确立的 stale-guard 写法）
   */
  let sessionsToken = 0;
  let recordsToken = 0;

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
        // 后端用 0 表示未评价（QaRecord.FEEDBACK_NONE），NULL 一并归到 0，
        // 这样「切回历史会话」时点赞/点踩的选中态能原样恢复（C2.6 验收）
        feedbackRating: record.feedbackRating ?? 0,
        streaming: false,
      });
    });
    return list;
  }

  /**
   * 按 id 定位消息。
   * 不用「数组最后一条」定位：历史会话是异步加载的，用 id 才能保证
   * 迟到的 SSE 回调不会写到别的消息上。
   */
  function patchMessage(id: string, patch: Partial<ChatMessage>): void {
    const target = messages.value.find((item) => item.id === id);
    if (target) {
      Object.assign(target, patch);
    }
  }

  /** 拉取某课程下的历史会话（课程切换后调用） */
  async function loadSessions(courseId: number): Promise<void> {
    const token = ++sessionsToken;
    loadingSessions.value = true;
    try {
      const list = await listSessions(courseId);
      if (token !== sessionsToken) return; // 已被更新的切课取代，连同后续 selectSession 一起跳过
      sessions.value = list;
      // 默认选中最近一条会话（列表按 createdAt 倒序，最新在最上）
      const first = list[0];
      if (first) {
        await selectSession(first.id);
      } else {
        startNewSession();
      }
    } finally {
      if (token === sessionsToken) {
        loadingSessions.value = false;
      }
    }
  }

  /** 切换会话并加载其历史问答 */
  async function selectSession(sessionId: number): Promise<void> {
    const token = ++recordsToken;
    // 切会话必须先断流：否则旧回答的 token 会写进刚加载出来的历史消息里（错位渲染）
    stopStream();
    // 先清空再加载（C3.1）：加载失败时若不清掉旧内容，侧栏已高亮新会话、消息区却还留着
    // 上一会话的内容 —— 用户以为在看新会话，实际看到的是旧问答（串会话）。
    messages.value = [];
    recordsError.value = '';
    loadingRecords.value = true;
    try {
      const records = await listRecords(sessionId);
      if (token !== recordsToken) return;
      // currentSessionId 必须在响应落地同一时刻才切换：
      // 若在 await 前赋值，连点两个会话时会出现「id 指向 B、消息区显示 A」的错位，此时提问挂错会话
      currentSessionId.value = sessionId;
      messages.value = toMessages(records);
    } catch (error) {
      // 不向上抛：两个调用方（侧栏点击、切课程后的自动选中）拿到 rejection 也只是吞掉。
      // 失败态改由 recordsError 驱动 → 消息区渲染「加载失败 + 重试」占位，而不是留白。
      // 只有仍属于本次调用的失败才落（过期响应不覆盖新状态）。
      if (token !== recordsToken) return;
      // 失败也要落 currentSessionId：用户明确点了这个会话，侧栏高亮与「重试」目标都该是它。
      // 有 recordsToken 守卫，这里不会被旧响应覆盖（连点竞态已在 token 比对处挡住）。
      currentSessionId.value = sessionId;
      recordsError.value = error instanceof Error ? error.message : '历史问答加载失败，请重试';
    } finally {
      if (token === recordsToken) {
        loadingRecords.value = false;
      }
    }
  }

  /**
   * 新建会话：断流 + 复位展示，首次提问时 sessionId 传 0 由后端懒创建（契约 4.2）。
   * 视图层不得绕过本方法直改 currentSessionId/messages —— 那样会漏掉断流与作废在途响应，
   * 出现「旧流还在跑、界面卡在正在生成且输入框禁用」的残留态。
   */
  function startNewSession(): void {
    recordsToken++;
    stopStream();
    currentSessionId.value = 0;
    messages.value = [];
    // C3.2 修缺陷：漏清失败态会让「上一次历史问答加载失败」的错误占位压住新建的空会话，
    // 用户唯一的出路是去点重试 —— 新建的会话永远进不去。
    recordsError.value = '';
  }

  /**
   * 发起一次提问并把回答流式上屏（C2.1）
   *
   * 流程：乐观插入「我的提问」+「AI 空回答」两条消息 → 发起 SSE →
   * references 挂到回答上 → message 增量交给打字机节流落地 → done 收尾并落 recordId。
   * sessionId 传当前值，为 0 时由后端懒创建会话（契约 4.2），真实 id 在 done 包里回传。
   */
  function sendQuestion(question: string, courseId: number): void {
    const text = question.trim();
    // 双保险：按钮已 disabled，这里再挡一次「回车连击」
    if (!text || streaming.value) return;

    const stamp = Date.now();
    const aiMessageId = `a-${stamp}`;

    messages.value.push({ id: `q-${stamp}`, role: 'user', content: text });
    messages.value.push({
      id: aiMessageId,
      role: 'ai',
      content: '',
      references: [],
      feedbackRating: 0,
      streaming: true,
    });
    streaming.value = true;

    const buffer = new TypewriterBuffer({
      onFlush: (chunk) => patchMessage(aiMessageId, { content: currentContent(aiMessageId) + chunk }),
    });
    activeBuffer = buffer;

    /**
     * 本次流收尾。只有「还属于本次调用」的缓冲才允许收尾 ——
     * 与 sseClient 的 abortController 局部引用守卫同理，防止旧流的迟到回调
     * 把新流的 streaming 状态提前复位。
     */
    const settle = (): void => {
      if (activeBuffer !== buffer) return;
      buffer.flush();
      activeBuffer = null;
      streaming.value = false;
      patchMessage(aiMessageId, { streaming: false });
    };

    void sseClient.startStream({
      courseId,
      question: text,
      sessionId: currentSessionId.value,
      callbacks: {
        onReferences: (references: SseReference[]) => {
          patchMessage(aiMessageId, { references });
        },
        onToken: (delta: string) => {
          // 只有仍属于「当前这次流」的缓冲才收字。用户点「停止生成」后 buffer 已被
          // settle/stopStream 释放（activeBuffer 置空），此时若还有一帧 token 在途，
          // 照收会让它排一次 rAF、在「停止」之后又蹦出字 —— 中断必须是终态。
          // 注：这是加固，不是可稳定复现的时序（实测该窗口≈一个微任务，抓不到），
          // 但把它做成显式不变式，比依赖调度顺序稳。
          if (activeBuffer !== buffer) return;
          buffer.push(delta);
        },
        onDone: (payload) => {
          // 懒创建：session 是本次 done 包才建出来的，必须落下来，否则下一问又新建一个会话
          if (payload.sessionId) {
            currentSessionId.value = payload.sessionId;
          }
          patchMessage(aiMessageId, { recordId: payload.recordId });
          settle();
        },
        onError: (payload) => {
          settle();
          // 已吐出的字保留，错误信息单独一行，不覆盖正文
          patchMessage(aiMessageId, { error: payload.message });
        },
      },
    });
  }

  /** 读某条消息的当前正文（打字机是「追加」语义） */
  function currentContent(id: string): string {
    return messages.value.find((item) => item.id === id)?.content ?? '';
  }

  /** 主动中断当前流（用户点「停止生成」／切会话／离开页面） */
  function stopStream(): void {
    sseClient.stopStream();
    // 中断不是错误：把已经到达的字保留下来，只复位流式态
    if (activeBuffer) {
      activeBuffer.flush();
      activeBuffer = null;
    }
    streaming.value = false;
    const last = messages.value[messages.value.length - 1];
    if (last?.role === 'ai') {
      last.streaming = false;
    }
  }

  /**
   * 点赞 / 点踩（C2.6）
   *
   * 契约：POST /api/qa/records/{id}/feedback，body { status: 1 | -1 }；
   * 未评价是 0，但**接口不接受 0**，所以「再点一次取消」做不到，点同一侧视为无操作。
   *
   * 乐观更新：先改本地选中态上屏，失败再回滚。点赞是轻量高频操作，
   * 等一个网络来回才有反馈体感很差；而请求失败时拦截器已弹过错误提示。
   * 只有「点击后状态没被后续点击改写」（target.feedbackRating 仍等于本次 status）才回滚，
   * 否则会把用户后来点的那一下一起抹掉。
   */
  async function setFeedback(messageId: string, status: 1 | -1): Promise<void> {
    const target = messages.value.find((item) => item.id === messageId);
    if (!target || target.role !== 'ai' || target.streaming) return;

    const recordId = target.recordId;
    // 没有 recordId 说明这条回答没落库（例如旧数据或 done 包异常），无从评价
    if (!recordId) return;
    // 已选中同一侧：接口不支持 0，无法取消，直接不发请求
    if (target.feedbackRating === status) return;

    const previous = target.feedbackRating ?? 0;
    target.feedbackRating = status;
    try {
      await submitFeedback(recordId, status);
    } catch {
      if (target.feedbackRating === status) {
        target.feedbackRating = previous;
      }
    }
  }

  /** 清空当前展示（新建会话 / 退出登录时调用） */
  function clear(): void {
    stopStream();
    sessions.value = [];
    currentSessionId.value = 0;
    messages.value = [];
    recordsError.value = '';
  }

  return {
    sessions,
    currentSessionId,
    messages,
    loadingSessions,
    loadingRecords,
    recordsError,
    streaming,
    loadSessions,
    selectSession,
    startNewSession,
    sendQuestion,
    setFeedback,
    stopStream,
    clear,
  };
});
