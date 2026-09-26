<template>
  <div class="chat-workspace">
    <!-- 顶部：当前课程 -->
    <header class="chat-header">
      <div class="header-title">
        {{ courseStore.currentCourse?.courseName ?? '请先选择课程' }}
      </div>
      <div v-if="courseStore.currentCourse?.description" class="header-desc">
        {{ courseStore.currentCourse.description }}
      </div>
    </header>

    <!-- 消息区 -->
    <el-scrollbar ref="scrollRef" class="chat-body" @scroll="handleScroll">
      <div v-if="chatStore.loadingRecords" class="chat-tip">加载中…</div>

      <el-empty
        v-else-if="chatStore.messages.length === 0"
        description="还没有问答记录，先选一门课程，然后在下方提问吧"
        :image-size="96"
      />

      <div v-else class="message-list">
        <div
          v-for="message in chatStore.messages"
          :key="message.id"
          class="message-row"
          :class="message.role === 'user' ? 'is-user' : 'is-ai'"
        >
          <div class="avatar" :class="message.role === 'user' ? 'avatar-user' : 'avatar-ai'">
            {{ message.role === 'user' ? '我' : 'AI' }}
          </div>

          <div class="bubble-wrap">
            <div class="bubble">
              <!-- AI 回答走 Markdown + 代码高亮（C2.2）；用户提问保持纯文本，
                   避免把用户输入原样当 Markdown 解释（转义风险与排版意外） -->
              <template v-if="message.role === 'ai'">
                <MarkdownViewer :content="message.content" />
                <span v-if="message.streaming" class="typing-caret" />
              </template>
              <div v-else class="bubble-text">{{ message.content }}</div>
              <!-- 流式中断/传输异常：单独一行，不覆盖已吐出的正文 -->
              <div v-if="message.error" class="bubble-error">{{ message.error }}</div>
            </div>

            <!-- 回答操作条：
                 C2.3 参考资料出处（有引用才出现）
                 C2.5 知识点精解（一键触发，只读）
                 C2.6 点赞 / 点踩（未落库时禁用，没有 recordId 无从评价） -->
            <div v-if="message.role === 'ai'" class="answer-actions">
              <el-tag
                v-if="(message.references?.length ?? 0) > 0"
                size="small"
                type="info"
                effect="plain"
                class="reference-tag"
                @click="openReferences(message.references ?? [])"
              >
                参考资料 ({{ message.references?.length }} 处)
              </el-tag>

              <el-button
                size="small"
                text
                :disabled="message.streaming"
                @click="openKnowledge(questionFor(message.id))"
              >
                知识点精解
              </el-button>

              <el-button
                size="small"
                text
                class="feedback-btn"
                :class="{ 'is-up': (message.feedbackRating ?? 0) === 1 }"
                :disabled="message.streaming || !message.recordId"
                @click="handleFeedback(message.id, 1)"
              >
                有帮助
              </el-button>

              <el-button
                size="small"
                text
                class="feedback-btn"
                :class="{ 'is-down': (message.feedbackRating ?? 0) === -1 }"
                :disabled="message.streaming || !message.recordId"
                @click="handleFeedback(message.id, -1)"
              >
                没帮助
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </el-scrollbar>

    <!-- 输入区：C2.1 接入 SSE 打字机流式 -->
    <footer class="chat-footer">
      <!--
        上限 1600 字（响应成员B在 PR #41 的提示）：提问是走 querystring 的 GET，
        约 1810 字会触发协议层 HTTP 431，请求进不到 Controller，
        EventSource 拿不到任何可读原因（前端只看到一个无信息的失败）。
        在输入端先挡住，并用字数计数器把限制暴露给用户，而不是静默截断。
      -->
      <el-input
        v-model="draft"
        type="textarea"
        :rows="3"
        resize="none"
        :disabled="chatStore.streaming"
        :maxlength="1600"
        show-word-limit
        placeholder="就当前课程提问（Enter 发送，Shift + Enter 换行）"
        @keydown="handleKeydown"
      />
      <div class="footer-actions">
        <span class="footer-tip">
          {{
            chatStore.streaming
              ? '正在生成，可点「停止生成」中断'
              : '回答仅基于本课程课件检索，可核对参考资料出处'
          }}
        </span>
        <div class="footer-buttons">
          <el-button v-if="chatStore.streaming" @click="handleStop">停止生成</el-button>
          <el-button type="primary" :disabled="!canSend" @click="handleSend">发送</el-button>
        </div>
      </div>
    </footer>

    <!-- 出处溯源抽屉（C2.3）与知识点精解面板（C2.5）：点气泡上的入口按钮打开 -->
    <GroundingDrawer ref="drawerRef" />
    <KnowledgePanel ref="knowledgeRef" />
  </div>
</template>

<script setup lang="ts">
/**
 * 智能答疑工作台（C2.1 打字机流式渲染 / C2.2 Markdown 渲染 / C2.3 出处抽屉 /
 *                   C2.5 知识点精解 / C2.6 点赞点踩）
 *
 * 本任务范围：真实 SSE → 流式吐字（节流）→ 自动跟随滚动 → 停止生成；
 *             AI 回答用 MarkdownViewer 渲染（代码高亮 + XSS 净化 + 一键复制）；
 *             点参考资料徽章打开 GroundingDrawer 溯源课件出处；
 *             一键触发 KnowledgePanel 看知识点精解（只读）；
 *             每条回答可点赞 / 点踩，选中态随历史会话一起恢复。
 *
 * 节流说明：打字机的「合并一帧内的多个 delta」在 utils/typewriter.ts 里做，
 * 本组件只负责「每帧一次滚动」——滚动跟着落地节奏走，不会比文本更新更频繁。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import type { ScrollbarInstance } from 'element-plus';
import { ElMessage } from 'element-plus';

import GroundingDrawer from '@/components/GroundingDrawer.vue';
import KnowledgePanel from '@/components/KnowledgePanel.vue';
import MarkdownViewer from '@/components/MarkdownViewer.vue';
import { useChatStore } from '@/stores/chatStore';
import { useCourseStore } from '@/stores/courseStore';
import type { SseReference } from '@/types';

const courseStore = useCourseStore();
const chatStore = useChatStore();

/** 输入草稿 */
const draft = ref('');
const scrollRef = ref<ScrollbarInstance>();
/** 出处抽屉：只在点徽章时按需打开，组件常驻但内部 v-model 控制显隐 */
const drawerRef = ref<InstanceType<typeof GroundingDrawer>>();
/** 知识点精解面板：同理，常驻 + 按需 open */
const knowledgeRef = ref<InstanceType<typeof KnowledgePanel>>();

/**
 * 视口是否贴底。
 * 贴底时才自动跟随滚动；用户手动上翻查看历史时不再把他拽回底部。
 */
const stickToBottom = ref(true);

const canSend = computed(() => draft.value.trim().length > 0 && !chatStore.streaming);

/** 最后一条消息：流式进行中它就是正在吐字的那条回答 */
const lastMessage = computed(() => chatStore.messages[chatStore.messages.length - 1]);

/**
 * ElScrollbar 的 wrapRef 在运行时已被 Vue 的 proxyRefs 解包成 HTMLDivElement，
 * 但 element-plus 生成的实例类型仍标注为 Ref<HTMLDivElement>（类型与运行时不一致）。
 * 这里按运行时真实形状收窄，而不是把整个实例断言成 any —— 只丢这一处的类型信息。
 */
type ScrollbarWrap = { wrapRef?: HTMLDivElement };
const scrollWrap = (): HTMLDivElement | undefined =>
  (scrollRef.value as unknown as ScrollbarWrap | undefined)?.wrapRef;

function scrollToBottom(): void {
  void nextTick(() => {
    const wrap = scrollWrap();
    // setScrollTop 传一个必然越界的大值，由滚动容器自行钳到最大值
    if (wrap) scrollRef.value?.setScrollTop(wrap.scrollHeight);
  });
}

/** 距底 80px 以内都算「还在底部」，避免像素级抖动导致跟随断掉 */
function handleScroll({ scrollTop }: { scrollTop: number }): void {
  const wrap = scrollWrap();
  if (!wrap) return;
  stickToBottom.value = wrap.scrollHeight - scrollTop - wrap.clientHeight < 80;
}

// 打字机每帧落地一次文本 → 这里每帧最多跟着滚一次（与节流同一节奏）
watch(
  () => lastMessage.value?.content,
  () => {
    if (stickToBottom.value) scrollToBottom();
  },
);

// 新消息入列（提问 + AI 占位）时先跳到底部，再开始吐字
watch(
  () => chatStore.messages.length,
  () => {
    stickToBottom.value = true;
    scrollToBottom();
  },
);

function handleSend(): void {
  const text = draft.value.trim();
  if (!text || chatStore.streaming) return;
  if (!courseStore.currentCourseId) {
    ElMessage.warning('请先在左侧选择课程');
    return;
  }
  chatStore.sendQuestion(text, courseStore.currentCourseId);
  draft.value = '';
  stickToBottom.value = true;
  scrollToBottom();
}

function handleKeydown(event: KeyboardEvent): void {
  // 只认不带 Shift 的 Enter；isComposing 让中文输入法的选词回车不算发送
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
  event.preventDefault();
  handleSend();
}

function handleStop(): void {
  chatStore.stopStream();
}

/** 打开出处抽屉：把该条回答的引用切块整包交给抽屉渲染 */
function openReferences(list: SseReference[]): void {
  drawerRef.value?.open(list);
}

/**
 * 取某条 AI 回答对应的提问（精解的 pointName 要用它）。
 * 不按「数组末条」取：历史会话里点旧回答的精解会串到最新一条提问上。
 * 从该消息往回找最近的 user 消息，找不到就返回空串（调用方拦截并提示）。
 */
function questionFor(messageId: string): string {
  const index = chatStore.messages.findIndex((item) => item.id === messageId);
  if (index < 0) return '';
  for (let i = index - 1; i >= 0; i -= 1) {
    const item = chatStore.messages[i];
    if (item.role === 'user') return item.content;
  }
  return '';
}

/** 一键触发知识点精解（C2.5）：精解要以课程为检索范围，没选课程就提示 */
function openKnowledge(question: string): void {
  if (!courseStore.currentCourseId) {
    ElMessage.warning('请先在左侧选择课程');
    return;
  }
  if (!question) {
    ElMessage.warning('找不到这条回答对应的提问，无法生成知识点精解');
    return;
  }
  // A 的 knowledgePoint 限 100 字符（KnowledgeGenerateDTO @Size）：整条提问直接送会得到
  // body.code=400「知识点名称过长」，先截断成短语级主题再触发精解
  const point = question.trim().slice(0, 100);
  void knowledgeRef.value?.open(courseStore.currentCourseId, point);
}

/** 点赞 / 点踩（C2.6）：真值处理与回滚都在 store 里，这里只做转发 */
function handleFeedback(messageId: string, status: 1 | -1): void {
  void chatStore.setFeedback(messageId, status);
}

onBeforeUnmount(() => {
  // 离开工作台必须断流：否则连接会挂到服务端超时，且迟到的 token 会写进已卸载的组件状态
  chatStore.stopStream();
});
</script>

<style scoped>
.chat-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.chat-header {
  flex-shrink: 0;
  padding: 16px 24px;
  border-bottom: 1px solid var(--app-border);
  background: #ffffff;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--app-text);
}

.header-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-text-muted);
}

.chat-body {
  flex: 1;
  min-height: 0;
  padding: 16px 24px;
}

.chat-tip {
  padding: 24px 0;
  font-size: 13px;
  color: var(--app-text-muted);
  text-align: center;
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message-row {
  display: flex;
  gap: 10px;
}

.message-row.is-user {
  flex-direction: row-reverse;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 600;
  color: #ffffff;
}

.avatar-user {
  background: var(--el-color-primary);
}

.avatar-ai {
  background: #0f766e;
}

.bubble-wrap {
  max-width: 76%;
}

.bubble {
  padding: 10px 14px;
  border-radius: 10px;
  border: 1px solid var(--app-border);
  background: #ffffff;
}

.is-user .bubble {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.bubble-text {
  font-size: 14px;
  line-height: 1.7;
  color: var(--app-text);
  white-space: pre-wrap;
  word-break: break-word;
}

/* 吐字光标：只在流式进行中出现，靠 CSS 动画闪烁，不额外触发重渲染 */
.typing-caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--el-color-primary);
  animation: caret-blink 1s steps(2, start) infinite;
}

@keyframes caret-blink {
  to {
    visibility: hidden;
  }
}

.bubble-error {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--app-border);
  font-size: 12px;
  color: var(--el-color-danger);
}

/* 回答操作条：参考资料 / 知识点精解 / 点赞点踩 排一行，窄屏自动换行 */
.answer-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-top: 6px;
}

.reference-tag {
  cursor: pointer;
  user-select: none;
}

.reference-tag:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-5);
}

/*
 * 反馈选中态用 class 而不是 `:type` 绑定：
 * text 型 el-button 的默认色由 .el-button.is-text 给出，
 * 这里靠"多一个类 + scoped 属性选择器"提高特异性盖掉它，避免为空字符串的 :type 取值。
 */
.feedback-btn.is-up {
  color: var(--el-color-primary);
  font-weight: 600;
}

.feedback-btn.is-down {
  color: var(--el-color-danger);
  font-weight: 600;
}

.chat-footer {
  flex-shrink: 0;
  padding: 12px 24px 16px;
  border-top: 1px solid var(--app-border);
  background: #ffffff;
}

.footer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.footer-tip {
  font-size: 12px;
  color: var(--app-text-muted);
}

.footer-buttons {
  display: flex;
  gap: 8px;
}
</style>
