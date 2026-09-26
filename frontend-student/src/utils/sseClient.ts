/**
 * SSE 流式请求封装（C1.3）
 * 设计来源：MEMBER_C_DEV_GUIDE.md 4.1
 *
 * 契约（DEV_SPECIFICATION.md 4.2，第 1 周末 Gate 1 冻结前以该文档为唯一标准）：
 *   GET /api/qa/chat/stream?courseId={id}&sessionId={id}&question={text}
 *   4 类事件：references / message / done / error，**所有 data 载荷一律为 JSON**
 *   - references → data: [{docId, fileName, chunkIndex, score, snippet}]
 *   - message    → data: {"delta": "..."}（增量吐字）
 *   - done       → data: {"recordId": 1024, "sessionId": 7, "finishReason": "stop", "totalTokens": 328}
 *   - error      → data: {"errorCode": 5001, "message": "..."}
 *
 * 为什么必须用 @microsoft/fetch-event-source（而不是原生 EventSource / 裸 fetch）：
 * - 原生 EventSource 不支持自定义请求头，带不上 `Authorization: Bearer <token>`；
 * - 裸 fetch 手搓 ReadableStream 解析 4 类事件易错。
 * 本文件为 C1.3 骨架：只负责「按契约区分 4 类事件并回调」，打字机节流与 UI 绑定在
 * C2.1 的 ChatWorkspace.vue 中实现，本文件不做渲染。
 */
import { fetchEventSource } from '@microsoft/fetch-event-source';

import { useUserStore } from '@/stores/userStore';
import type { SseDonePayload, SseErrorPayload, SseReference } from '@/types';

/** SSE 接口路径（契约冻结值，加一个字符就是 404） */
export const SSE_CHAT_PATH = '/api/qa/chat/stream';

/** 4 类事件名（禁止别名） */
export type SseEventName = 'references' | 'message' | 'done' | 'error';

export interface SseChatCallbacks {
  /** 首包：命中的课件出处 */
  onReferences?: (references: SseReference[]) => void;
  /** 增量吐字：解析后的 delta 文本 */
  onToken?: (delta: string) => void;
  /** 结束包：携带 recordId / sessionId（点赞点踩必须用到 recordId） */
  onDone?: (payload: SseDonePayload) => void;
  /** 异常中断包 */
  onError?: (payload: SseErrorPayload) => void;
}

export interface SseStartOptions {
  courseId: number;
  question: string;
  /** 传 0 或不传 → 后端懒创建会话，并在 done 包回传真实 sessionId */
  sessionId?: number;
  callbacks: SseChatCallbacks;
}

/** 解析 JSON，失败返回 null（不抛错，避免单帧脏数据打断整条流） */
function parseJson<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

export class SseChatClient {
  private abortController: AbortController | null = null;

  /** 发起一次流式提问；同一实例重复调用会先中断上一次请求 */
  public async startStream(options: SseStartOptions): Promise<void> {
    const { courseId, question, sessionId = 0, callbacks } = options;

    this.stopStream();
    // 用局部 controller 引用做守卫：重叠调用（连发两次提问）时，被中断的旧请求其
    // finally 不能把新请求刚装入的 this.abortController 误清空，否则「停止生成」失效、
    // 新连接泄漏挂到服务端 SSE 超时。
    const controller = new AbortController();
    this.abortController = controller;

    // onError 去重：fetch-event-source 的 onerror 抛出后会被 reject 落进外层 catch，
    // 若两处都回调会导致一次断连弹两条错误、消息被 push 两遍、streaming 标记重复复位。
    // 用闭包标志保证整次调用只对外通知一次错误。
    let errorNotified = false;
    const notifyError = (message: string): void => {
      if (errorNotified) return;
      errorNotified = true;
      callbacks.onError?.({ errorCode: -1, message });
    };

    // token 键名统一 satoken（只是本地存储名，与请求头名无关）
    const token = localStorage.getItem('satoken') ?? '';
    const url = `${SSE_CHAT_PATH}?courseId=${courseId}&sessionId=${sessionId}&question=${encodeURIComponent(question)}`;

    try {
      await fetchEventSource(url, {
        method: 'GET',
        headers: {
          // 头值必须带 "Bearer " 前缀（含一个空格）：sa-token 配的是
          // token-name=Authorization + token-prefix=Bearer，只发裸 token 会被判未登录返回 401
          Authorization: `Bearer ${token}`,
          Accept: 'text/event-stream',
        },
        signal: controller.signal,
        // 页面切到后台时不断开流（长回答场景）
        openWhenHidden: true,

        // 自定义 onopen：本项目契约「传输层恒 HTTP 200，业务码只放 body」，未登录(401)
        // 与限流(429) 都以 content-type=application/json 的 Result 包返回。库默认 onopen
        // 只做 content-type 断言并抛底层错误，前端无从区分「掉登录」与「被限流」。这里
        // 主动读 body.code：401 复用与 request.ts 一致的 clearSession() 登出 + 带 redirect 跳登录。
        async onopen(response) {
          const contentType = response.headers.get('content-type') ?? '';
          if (response.ok && contentType.includes('text/event-stream')) {
            return;
          }
          const body = (await response.json().catch(() => null)) as
            | { code?: number; message?: string }
            | null;
          const code = body?.code ?? response.status;
          if (code === 401) {
            useUserStore().clearSession();
            const current = encodeURIComponent(
              window.location.pathname + window.location.search,
            );
            window.location.href = `/login?redirect=${current}`;
          }
          throw new Error(body?.message ?? `流式请求失败(${code})`);
        },

        onmessage(msg) {
          // 通过 event 字段区分 4 类事件，全部按 JSON 处理（契约要求）
          // C3.2：这里原先对每一帧打一条 console.debug（含 data 全文）。SSE 是按 token 推的，
          // 一段 500 字回答会产生上千次 console I/O 并重复打印整段正文 —— 控制台被刷爆，
          // 每次打印还要拼字符串，纯属给主线程添负担。失败原因由外层 catch 的 notifyError 统一暴露。
          switch (msg.event as SseEventName) {
            case 'references': {
              const references = parseJson<SseReference[]>(msg.data) ?? [];
              callbacks.onReferences?.(references);
              break;
            }
            case 'message': {
              // 契约载荷为 {"delta": "..."}；解析失败时降级按裸文本处理（兼容保护）
              const payload = parseJson<{ delta?: string }>(msg.data);
              callbacks.onToken?.(payload?.delta ?? msg.data);
              break;
            }
            case 'done': {
              // done 包必含 recordId，必须保存下来供点赞/点踩使用
              const payload = parseJson<SseDonePayload>(msg.data);
              if (payload) {
                callbacks.onDone?.(payload);
              } else {
                notifyError('done 事件载荷解析失败');
              }
              break;
            }
            case 'error': {
              const payload = parseJson<SseErrorPayload>(msg.data) ?? {
                errorCode: -1,
                message: msg.data,
              };
              notifyError(payload.message);
              break;
            }
            default: {
              // 契约只有 4 类事件；出现未知事件名说明后端契约已变更，暴露出来而不是吞掉
              console.warn('[sseClient] 收到契约外的 SSE 事件：', msg.event);
            }
          }
        },

        onerror(err) {
          // 抛出以阻止库默认重试；错误上报统一交给外层 catch 的 notifyError（去重）
          throw err;
        },

        onclose() {
          // 服务端正常关闭；是否需要额外处理由调用方在 onDone 里决定
        },
      });
    } catch (error: unknown) {
      // 用户主动中断（停止生成 / 切换会话）不是错误，不回调 onError
      if (error instanceof Error && error.name === 'AbortError') {
        return;
      }
      notifyError(error instanceof Error ? error.message : String(error));
    } finally {
      // 只清理属于本次调用的 controller，避免误清新请求的（重叠调用竞态）
      if (this.abortController === controller) {
        this.abortController = null;
      }
    }
  }

  /** 中断当前流（用户点「停止生成」或切换会话时调用，防止内存泄漏与错位渲染） */
  public stopStream(): void {
    if (this.abortController) {
      this.abortController.abort();
      this.abortController = null;
    }
  }

  /** 当前是否有流在进行中 */
  public get isStreaming(): boolean {
    return this.abortController !== null;
  }
}
