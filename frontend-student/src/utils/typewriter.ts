/**
 * 打字机节流缓冲（C2.1）
 *
 * 为什么需要它：SSE 的 `message` 事件是「服务端按 token 逐帧推」的，一帧一个 delta。
 * 如果收到一帧就直接改一次响应式文本，500 字的长回答会在一两秒内触发上百次组件更新
 * —— 掉帧的根因不是 DOM 太重，而是**渲染次数等于网络帧数**。
 *
 * 做法：delta 先堆进缓冲，用 requestAnimationFrame 把「一帧内到达的 N 个 delta」
 * 合并成「一次文本更新 + 一次滚动」。于是渲染次数被硬性压到 ≤ 刷新率上限（约 60 次/秒），
 * 服务端再快也不会掉帧，视觉上依然是逐字吐字（网络本身是分帧到的）。
 *
 * 与 @microsoft/fetch-event-source 的关系：本模块只做「节流 + 合并」，不碰网络。
 * 不依赖 Vue / 浏览器全局（可注入 requestFrame 便于自测），因此可单独验证。
 */

export interface TypewriterHandlers {
  /** 合并后的文本落地回调（每帧最多触发一次） */
  onFlush: (text: string) => void;
  /** 调度一帧；默认 window.requestAnimationFrame，测试时可注入 */
  requestFrame?: (callback: FrameRequestCallback) => number;
  /** 取消已调度的一帧；默认 window.cancelAnimationFrame */
  cancelFrame?: (handle: number) => void;
}

export class TypewriterBuffer {
  /** 尚未落地的增量文本 */
  private pending = '';
  /** 已排队但未执行的那一帧（null 表示没有排队） */
  private frameHandle: number | null = null;

  private readonly onFlush: (text: string) => void;
  private readonly requestFrame: (callback: FrameRequestCallback) => number;
  private readonly cancelFrame: (handle: number) => void;

  constructor(handlers: TypewriterHandlers) {
    this.onFlush = handlers.onFlush;
    this.requestFrame =
      handlers.requestFrame ?? ((callback) => window.requestAnimationFrame(callback));
    this.cancelFrame = handlers.cancelFrame ?? ((handle) => window.cancelAnimationFrame(handle));
  }

  /**
   * 收一个增量 token。
   * 同一帧内调用多次，只会在下一帧合并落地一次 —— 这就是「节流」的全部。
   */
  public push(delta: string): void {
    if (!delta) return;
    this.pending += delta;
    this.schedule();
  }

  /**
   * 立即把缓冲里剩的字全部落地。
   * 流结束（done/error）与用户点「停止生成」时都要调用，否则最后不到一帧的尾字会丢。
   */
  public flush(): void {
    this.cancelScheduled();
    if (!this.pending) return;
    const text = this.pending;
    this.pending = '';
    this.onFlush(text);
  }

  /** 丢弃未落地的缓冲并停止调度（切换会话 / 组件卸载，不再需要这些字） */
  public dispose(): void {
    this.cancelScheduled();
    this.pending = '';
  }

  /** 当前缓冲里的字符数（未落地）；供自测断言使用 */
  public get bufferedLength(): number {
    return this.pending.length;
  }

  /** 是否已排队等待下一帧 */
  public get scheduled(): boolean {
    return this.frameHandle !== null;
  }

  private schedule(): void {
    // 本帧已排过队就直接等它执行，不重复排队（否则一帧内 N 个 delta 会排 N 次）
    if (this.frameHandle !== null) return;
    this.frameHandle = this.requestFrame(() => {
      this.frameHandle = null;
      this.flush();
    });
  }

  private cancelScheduled(): void {
    if (this.frameHandle !== null) {
      this.cancelFrame(this.frameHandle);
      this.frameHandle = null;
    }
  }
}
