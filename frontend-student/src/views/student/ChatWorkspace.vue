<template>
  <div class="chat-workspace">
    <!-- 顶部现代化 Header -->
    <header class="chat-header">
      <div class="header-main">
        <div class="header-title">
          {{ courseStore.currentCourse?.courseName ?? '请选择课程' }}
        </div>
        <div v-if="courseStore.currentCourse?.description" class="header-desc">
          {{ courseStore.currentCourse.description }}
        </div>
      </div>
    </header>

    <!-- 消息流主视口（居中呼吸宽度 860px） -->
    <el-scrollbar ref="scrollRef" class="chat-body" @scroll="handleScroll">
      <div class="chat-container">
        <!-- 错误态与加载中 -->
        <StatePlaceholder
          v-if="bodyError"
          state="error"
          error-title="加载失败"
          :description="bodyError"
          :retryable="canRetryBody"
          @retry="handleRetryRecords"
        />
        <StatePlaceholder v-else-if="chatStore.loadingRecords" state="loading" :rows="5" />

        <!-- 现代化欢迎空态与快捷推荐提问 -->
        <div v-else-if="chatStore.messages.length === 0" class="welcome-empty-state">
          <div class="welcome-card">
            <div class="welcome-avatar">
              <el-icon :size="32"><MagicStick /></el-icon>
            </div>
            <h2 class="welcome-title">欢迎使用课程 AI 智能学伴</h2>
            <p class="welcome-subtitle">
              基于《{{ courseStore.currentCourse?.courseName || '当前课程' }}》课件知识库，为您提供实时答疑、考点剖析与启发式导学。
            </p>
            <div class="quick-prompts-label">✨ 您可以试着这样提问：</div>
            <div class="quick-prompts-grid">
              <div
                v-for="(prompt, idx) in quickPrompts"
                :key="idx"
                class="prompt-pill-card"
                @click="applyQuickPrompt(prompt)"
              >
                <el-icon class="pill-icon"><ChatLineRound /></el-icon>
                <span class="pill-text">{{ prompt }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 消息流对话列表 -->
        <div v-else class="message-list">
          <div
            v-for="message in chatStore.messages"
            :key="message.id"
            class="message-row"
            :class="message.role === 'user' ? 'is-user' : 'is-ai'"
          >
            <!-- 角色头像 -->
            <div class="avatar" :class="message.role === 'user' ? 'avatar-user' : 'avatar-ai'">
              <span v-if="message.role === 'user'">我</span>
              <el-icon v-else :size="16"><MagicStick /></el-icon>
            </div>

            <div class="bubble-wrap">
              <div class="bubble">
                <!-- AI 回答区 -->
                <template v-if="message.role === 'ai'">
                  <!-- 【核心需求 1】首字到达前展示“思考中”动效 -->
                  <div v-if="message.streaming && !message.content" class="thinking-state-box">
                    <div class="thinking-pulse-ring">
                      <el-icon class="thinking-icon is-loading"><Loading /></el-icon>
                    </div>
                    <div class="thinking-text-group">
                      <div class="thinking-main-text">智能学伴正在深入思考中...</div>
                      <div class="thinking-sub-text">
                        正在查阅《{{ courseStore.currentCourse?.courseName || '课程' }}》课件知识库，组织严谨结构化解答
                      </div>
                    </div>
                    <div class="thinking-dots">
                      <span class="dot"></span>
                      <span class="dot"></span>
                      <span class="dot"></span>
                    </div>
                  </div>

                  <!-- 正文输出：Markdown 渲染 -->
                  <MarkdownViewer v-if="message.content" :content="message.content" />
                  <span v-if="message.streaming && message.content" class="typing-caret" />
                </template>

                <!-- 用户提问区（含图片/文档附件展示） -->
                <template v-else>
                  <!-- 附件卡片展示 -->
                  <div v-if="message.attachment" class="message-attachment-card">
                    <div v-if="message.attachment.fileType === 'image'" class="image-attachment-wrap">
                      <el-image
                        :src="message.attachment.fileUrl"
                        :preview-src-list="[message.attachment.fileUrl]"
                        fit="cover"
                        class="attached-preview-image"
                      />
                    </div>
                    <div v-else class="doc-attachment-badge">
                      <el-icon :size="18" class="doc-icon"><Document /></el-icon>
                      <div class="doc-info">
                        <span class="doc-name">{{ message.attachment.fileName }}</span>
                        <span class="doc-tip">附带参考文档（已自动提取上下文）</span>
                      </div>
                    </div>
                  </div>
                  <div v-if="message.content" class="bubble-text">{{ message.content }}</div>
                </template>

                <!-- 流式中断/异常提示 -->
                <div v-if="message.error" class="bubble-error">{{ message.error }}</div>
              </div>

              <!-- AI 操作条 -->
              <div v-if="message.role === 'ai'" class="answer-actions">
                <el-tag
                  v-if="(message.references?.length ?? 0) > 0"
                  size="small"
                  type="info"
                  effect="plain"
                  class="reference-tag"
                  @click="openReferences(message.references ?? [])"
                >
                  课件溯源 ({{ message.references?.length }} 处)
                </el-tag>

                <el-button
                  size="small"
                  text
                  class="action-capsule"
                  :disabled="message.streaming"
                  @click="openKnowledge(questionFor(message.id))"
                >
                  <el-icon><HelpFilled /></el-icon>
                  知识点精解
                </el-button>

                <el-button
                  size="small"
                  text
                  class="feedback-btn action-capsule"
                  :class="{ 'is-up': (message.feedbackRating ?? 0) === 1 }"
                  :disabled="message.streaming || !message.recordId"
                  @click="handleFeedback(message.id, 1)"
                >
                  有帮助
                </el-button>

                <el-button
                  size="small"
                  text
                  class="feedback-btn action-capsule"
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
      </div>
    </el-scrollbar>

    <!-- 底部悬浮式现代化输入区 -->
    <footer class="chat-footer">
      <div class="footer-container">
        <!-- 待发送附件展示预览条 -->
        <div v-if="attachedFile" class="attachment-staging-bar">
          <div class="staging-card">
            <el-icon class="staging-icon" v-if="attachedFile.fileType === 'image'"><Picture /></el-icon>
            <el-icon class="staging-icon" v-else><Document /></el-icon>
            <div class="staging-meta">
              <span class="staging-name">{{ attachedFile.fileName }}</span>
              <span class="staging-type">{{ attachedFile.fileType === 'image' ? '图片' : '文档 (已提取文本)' }}</span>
            </div>
            <button class="staging-remove-btn" @click="removeAttachment">
              <el-icon :size="14"><Close /></el-icon>
            </button>
          </div>
        </div>

        <!-- 输入框复合容器 -->
        <div class="input-composer-box">
          <textarea
            v-model="draft"
            rows="2"
            class="composer-textarea"
            :disabled="chatStore.streaming"
            maxlength="1600"
            placeholder="向 AI 智能学伴提问课程内容（Enter 发送，Shift + Enter 换行）..."
            @keydown="handleKeydown"
          ></textarea>

          <!-- 工具栏：图片上传、文档上传、字数与发送 -->
          <div class="composer-toolbar">
            <div class="toolbar-left">
              <!-- 隐藏的本地文件 input -->
              <input
                ref="imageInputRef"
                type="file"
                accept="image/*"
                style="display: none"
                @change="handleFileSelected($event, 'image')"
              />
              <input
                ref="docInputRef"
                type="file"
                accept=".pdf,.docx,.md,.txt"
                style="display: none"
                @change="handleFileSelected($event, 'doc')"
              />

              <el-tooltip content="上传题目或课件截图" placement="top">
                <button
                  class="tool-btn"
                  :disabled="uploading || chatStore.streaming"
                  @click="triggerImageSelect"
                >
                  <el-icon v-if="uploading && uploadType === 'image'" class="is-loading"><Loading /></el-icon>
                  <el-icon v-else><Picture /></el-icon>
                  <span>图片</span>
                </button>
              </el-tooltip>

              <el-tooltip content="上传参考课件或习题文档 (PDF/DOCX/MD/TXT)" placement="top">
                <button
                  class="tool-btn"
                  :disabled="uploading || chatStore.streaming"
                  @click="triggerDocSelect"
                >
                  <el-icon v-if="uploading && uploadType === 'doc'" class="is-loading"><Loading /></el-icon>
                  <el-icon v-else><Document /></el-icon>
                  <span>文档</span>
                </button>
              </el-tooltip>
            </div>

            <div class="toolbar-right">
              <span class="char-count">{{ draft.length }}/1600</span>
              <el-button
                v-if="chatStore.streaming"
                size="small"
                round
                @click="handleStop"
              >
                停止生成
              </el-button>
              <el-button
                v-else
                type="primary"
                round
                class="send-btn"
                :disabled="!canSubmit"
                @click="handleSend"
              >
                <el-icon><Promotion /></el-icon>
                <span>发送</span>
              </el-button>
            </div>
          </div>
        </div>

        <div class="footer-bottom-tip">
          <span>{{ footerTip }}</span>
        </div>
      </div>
    </footer>

    <!-- 出处溯源抽屉与知识点精解面板 -->
    <GroundingDrawer ref="drawerRef" />
    <KnowledgePanel ref="knowledgeRef" />
  </div>
</template>

<script setup lang="ts">
/**
 * 智能答疑工作台（C2.1 打字机流式渲染 / C2.2 Markdown 渲染 / C2.3 出处抽屉 /
 *                   C2.5 知识点精解 / C2.6 点赞点踩 / 思考中动效 / 附件支持）
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import type { ScrollbarInstance } from 'element-plus';
import { ElMessage } from 'element-plus';
import {
  ChatLineRound,
  Close,
  Document,
  HelpFilled,
  Loading,
  MagicStick,
  Picture,
  Promotion,
} from '@element-plus/icons-vue';

import GroundingDrawer from '@/components/GroundingDrawer.vue';
import KnowledgePanel from '@/components/KnowledgePanel.vue';
import MarkdownViewer from '@/components/MarkdownViewer.vue';
import StatePlaceholder from '@/components/StatePlaceholder.vue';
import { uploadAttachment } from '@/api/qa';
import { useChatStore } from '@/stores/chatStore';
import { useCourseStore } from '@/stores/courseStore';
import type { AttachmentInfo, SseReference } from '@/types';

const courseStore = useCourseStore();
const chatStore = useChatStore();

/**
 * 消息区失败原因（C3.1）
 */
const bodyError = computed(() => courseStore.coursesError || chatStore.recordsError);

/**
 * 只有「历史问答加载失败」能就地重试
 */
const canRetryBody = computed(
  () => !courseStore.coursesError && chatStore.currentSessionId > 0,
);

/** 重拉当前会话的历史问答（C3.1 错误态的重试入口） */
function handleRetryRecords(): void {
  void chatStore.selectSession(chatStore.currentSessionId);
}

/** 全学科通用智能导学推荐提问列表（动态结合当前修读课程） */
const quickPrompts = computed(() => {
  const courseName = courseStore.currentCourse?.courseName || '本课程';
  return [
    `请帮我梳理《${courseName}》的核心知识体系与章节脉络`,
    `《${courseName}》中有哪些高频易混淆的核心概念？请做对比辨析`,
    `结合课件知识库，本课程有哪些典型考核重点与易错考点？`,
    `我有一道课后疑难题目，请以启发式教学引导我逐步理清思路`,
  ];
});

function applyQuickPrompt(promptText: string): void {
  draft.value = promptText;
}

/** 输入草稿与附件状态 */
const draft = ref('');
const attachedFile = ref<AttachmentInfo | null>(null);
const uploading = ref(false);
const uploadType = ref<'image' | 'doc'>('image');
const imageInputRef = ref<HTMLInputElement | null>(null);
const docInputRef = ref<HTMLInputElement | null>(null);

const scrollRef = ref<ScrollbarInstance>();
/** 出处抽屉 */
const drawerRef = ref<InstanceType<typeof GroundingDrawer>>();
/** 知识点精解面板 */
const knowledgeRef = ref<InstanceType<typeof KnowledgePanel>>();

/**
 * 视口是否贴底
 */
const stickToBottom = ref(true);

/** 触发图片选择 */
function triggerImageSelect(): void {
  if (!courseStore.currentCourseId) {
    ElMessage.warning('请先在左侧选择课程');
    return;
  }
  imageInputRef.value?.click();
}

/** 触发文档选择 */
function triggerDocSelect(): void {
  if (!courseStore.currentCourseId) {
    ElMessage.warning('请先在左侧选择课程');
    return;
  }
  docInputRef.value?.click();
}

/** 文件选择回调与上传 */
async function handleFileSelected(event: Event, type: 'image' | 'doc'): Promise<void> {
  const target = event.target as HTMLInputElement;
  const file = target.files?.[0];
  if (!file) return;

  // 大小限制：图片 <= 10MB，文档 <= 20MB
  const maxBytes = type === 'image' ? 10 * 1024 * 1024 : 20 * 1024 * 1024;
  if (file.size > maxBytes) {
    ElMessage.warning(`上传文件不能超过 ${type === 'image' ? '10MB' : '20MB'}`);
    target.value = '';
    return;
  }

  uploading.value = true;
  uploadType.value = type;

  try {
    const res = await uploadAttachment(file);
    attachedFile.value = res;
    ElMessage.success(type === 'image' ? '图片上传成功' : '文档已上传并提取文本');
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '附件上传失败，请重试';
    ElMessage.error(msg);
  } finally {
    uploading.value = false;
    target.value = '';
  }
}

/** 移除已选附件 */
function removeAttachment(): void {
  attachedFile.value = null;
}

/**
 * 唯一「可发送」判定（#59 增强版）：
 * 允许有文本或者有附件，但在生成中、加载会话列表或加载明细时禁用
 */
const canSubmit = computed(() => {
  const hasContent = draft.value.trim().length > 0 || attachedFile.value !== null;
  return (
    hasContent &&
    !chatStore.streaming &&
    !chatStore.loadingSessions &&
    !chatStore.loadingRecords
  );
});

/** 底部提示 */
const footerTip = computed(() => {
  if (chatStore.streaming) return '正在生成，可点「停止生成」中断';
  if (chatStore.loadingSessions || chatStore.loadingRecords) {
    return '正在加载课程数据，加载完成后即可提问';
  }
  return 'AI 智能学伴基于本课程课件检索库生成回答，请仔细甄别参考资料出处';
});

/** 最后一条消息 */
const lastMessage = computed(() => chatStore.messages[chatStore.messages.length - 1]);

type ScrollbarWrap = { wrapRef?: HTMLDivElement };
const scrollWrap = (): HTMLDivElement | undefined =>
  (scrollRef.value as unknown as ScrollbarWrap | undefined)?.wrapRef;

function scrollToBottom(): void {
  void nextTick(() => {
    const wrap = scrollWrap();
    if (wrap) scrollRef.value?.setScrollTop(wrap.scrollHeight);
  });
}

function handleScroll({ scrollTop }: { scrollTop: number }): void {
  const wrap = scrollWrap();
  if (!wrap) return;
  stickToBottom.value = wrap.scrollHeight - scrollTop - wrap.clientHeight < 80;
}

watch(
  () => lastMessage.value?.content,
  () => {
    if (stickToBottom.value) scrollToBottom();
  },
);

watch(
  () => chatStore.messages.length,
  () => {
    stickToBottom.value = true;
    scrollToBottom();
  },
);

function handleSend(): void {
  if (!canSubmit.value) return;
  let text = draft.value.trim();
  const attachment = attachedFile.value;

  if (!courseStore.currentCourseId) {
    ElMessage.warning('请先在左侧选择课程');
    return;
  }

  if (!text && attachment) {
    text = attachment.fileType === 'image' ? '请解析这张图片中的题目或课件内容' : '请根据我上传的文档进行分析与解答';
  }

  chatStore.sendQuestion(text, courseStore.currentCourseId, attachment ?? undefined);
  draft.value = '';
  attachedFile.value = null;
  stickToBottom.value = true;
  scrollToBottom();
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
  event.preventDefault();
  if (!canSubmit.value) return;
  handleSend();
}

function handleStop(): void {
  chatStore.stopStream();
}

function openReferences(list: SseReference[]): void {
  drawerRef.value?.open(list);
}

function questionFor(messageId: string): string {
  const index = chatStore.messages.findIndex((item) => item.id === messageId);
  if (index < 0) return '';
  for (let i = index - 1; i >= 0; i -= 1) {
    const item = chatStore.messages[i];
    if (item.role === 'user') return item.content;
  }
  return '';
}

function openKnowledge(question: string): void {
  if (!courseStore.currentCourseId) {
    ElMessage.warning('请先在左侧选择课程');
    return;
  }
  if (!question) {
    ElMessage.warning('找不到这条回答对应的提问，无法生成知识点精解');
    return;
  }
  const point = question.trim().slice(0, 100);
  void knowledgeRef.value?.open(courseStore.currentCourseId, point);
}

function handleFeedback(messageId: string, status: 1 | -1): void {
  void chatStore.setFeedback(messageId, status);
}

onBeforeUnmount(() => {
  chatStore.stopStream();
});
</script>

<style scoped>
.chat-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background-color: var(--app-bg);
}

/* 顶部头部 */
.chat-header {
  flex-shrink: 0;
  padding: 14px 28px;
  background: var(--app-surface);
  border-bottom: 1px solid var(--app-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--app-text);
  letter-spacing: -0.2px;
}

.header-desc {
  font-size: 13px;
  color: var(--app-text-muted);
  max-width: 480px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.course-code-tag {
  font-weight: 500;
  border-radius: 4px;
}

/* 消息流主体 */
.chat-body {
  flex: 1;
  min-height: 0;
}

.chat-container {
  max-width: 860px;
  margin: 0 auto;
  padding: 24px 20px 48px;
  width: 100%;
}

/* 空态与欢迎导引卡片 */
.welcome-empty-state {
  padding: 36px 0;
}

.welcome-card {
  background: var(--app-surface);
  border-radius: 12px;
  padding: 36px 32px;
  text-align: center;
  border: 1px solid var(--app-border);
}

.welcome-avatar {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: #2563eb;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 16px;
}

.welcome-title {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 8px;
}

.welcome-subtitle {
  font-size: 13px;
  color: #64748b;
  line-height: 1.6;
  max-width: 580px;
  margin: 0 auto 28px;
}

.quick-prompts-label {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  text-align: left;
  margin-bottom: 12px;
}

.quick-prompts-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px;
}

.prompt-pill-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: #ffffff;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  cursor: pointer;
  text-align: left;
  transition: all 0.15s ease;
}

.prompt-pill-card:hover {
  background: #eff6ff;
  border-color: #93c5fd;
  transform: translateY(-1px);
}

.pill-icon {
  color: #2563eb;
  font-size: 16px;
  flex-shrink: 0;
}

.pill-text {
  font-size: 13px;
  color: #334155;
  line-height: 1.45;
}

/* 消息列表 */
.message-list {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.message-row {
  display: flex;
  gap: 14px;
}

.message-row.is-user {
  flex-direction: row-reverse;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: #ffffff;
}

.avatar-user {
  background: #1e3a8a;
}

.avatar-ai {
  background: #2563eb;
}

.bubble-wrap {
  max-width: 82%;
  display: flex;
  flex-direction: column;
}

.bubble {
  padding: 14px 18px;
  border-radius: 10px;
  word-break: break-word;
  font-size: 14px;
  line-height: 1.7;
}

.is-user .bubble {
  background: #1d4ed8;
  color: #ffffff;
  border-radius: 10px 2px 10px 10px;
}

.is-ai .bubble {
  background: #ffffff;
  border: 1px solid var(--app-border);
  color: #0f172a;
  border-radius: 2px 10px 10px 10px;
}

.bubble-text {
  white-space: pre-wrap;
}

/* 【核心需求 1】AI「思考中」动效样式（现代学府蓝呼吸质感） */
.thinking-state-box {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #f0f7ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
}

.thinking-pulse-ring {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #dbeafe;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #1d4ed8;
  flex-shrink: 0;
  animation: pulse-scale 2s infinite ease-in-out;
}

@keyframes pulse-scale {
  0%, 100% {
    transform: scale(1);
    box-shadow: 0 0 0 0 rgba(37, 99, 235, 0.25);
  }
  50% {
    transform: scale(1.05);
    box-shadow: 0 0 0 6px rgba(37, 99, 235, 0);
  }
}

.thinking-icon {
  font-size: 16px;
}

.thinking-text-group {
  display: flex;
  flex-direction: column;
}

.thinking-main-text {
  font-size: 13px;
  font-weight: 600;
  color: #1e40af;
}

.thinking-sub-text {
  font-size: 11px;
  color: #64748b;
  margin-top: 2px;
}

.thinking-dots {
  display: flex;
  gap: 4px;
  align-items: center;
  margin-left: auto;
}

.thinking-dots .dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #2563eb;
  animation: dot-jump 1.4s infinite ease-in-out both;
}

.thinking-dots .dot:nth-child(1) {
  animation-delay: -0.32s;
}

.thinking-dots .dot:nth-child(2) {
  animation-delay: -0.16s;
}

@keyframes dot-jump {
  0%, 80%, 100% {
    transform: scale(0.6);
    opacity: 0.4;
  }
  40% {
    transform: scale(1.2);
    opacity: 1;
  }
}

/* 吐字光标 */
.typing-caret {
  display: inline-block;
  width: 2px;
  height: 1.1em;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: #2563eb;
  animation: caret-blink 1s steps(2, start) infinite;
}

@keyframes caret-blink {
  to {
    visibility: hidden;
  }
}

/* 用户提问附件展示 */
.message-attachment-card {
  margin-bottom: 10px;
}

.image-attachment-wrap {
  border-radius: 8px;
  overflow: hidden;
  display: inline-block;
  border: 1px solid rgba(255, 255, 255, 0.3);
}

.attached-preview-image {
  max-width: 260px;
  max-height: 200px;
  display: block;
}

.doc-attachment-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.15);
  border: 1px solid rgba(255, 255, 255, 0.25);
  border-radius: 8px;
}

.doc-icon {
  font-size: 18px;
}

.doc-info {
  display: flex;
  flex-direction: column;
}

.doc-name {
  font-size: 13px;
  font-weight: 500;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-tip {
  font-size: 10px;
  opacity: 0.85;
  margin-top: 1px;
}

/* 异常提示 */
.bubble-error {
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed #fca5a5;
  font-size: 12px;
  color: #ef4444;
}

/* AI 回答操作条 */
.answer-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
  padding-left: 4px;
}

.reference-tag {
  cursor: pointer;
  border-radius: 4px;
  background: #eff6ff;
  color: #1d4ed8;
  border-color: #bfdbfe;
}

.reference-tag:hover {
  color: #1e40af;
  border-color: #93c5fd;
  background: #dbeafe;
}

.action-capsule {
  border-radius: 4px;
  font-size: 12px;
  padding: 4px 10px;
  height: auto;
}

.feedback-btn.is-up {
  color: #2563eb;
  font-weight: 600;
}

.feedback-btn.is-down {
  color: #dc2626;
  font-weight: 600;
}

/* 底部悬浮输入区域 */
.chat-footer {
  flex-shrink: 0;
  padding: 12px 28px 16px;
  background: var(--app-surface);
  border-top: 1px solid var(--app-border);
}

.footer-container {
  max-width: 860px;
  margin: 0 auto;
}

/* 暂存附件条 */
.attachment-staging-bar {
  margin-bottom: 8px;
}

.staging-card {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: #f1f5f9;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
}

.staging-icon {
  font-size: 16px;
  color: #2563eb;
}

.staging-meta {
  display: flex;
  flex-direction: column;
}

.staging-name {
  font-size: 12px;
  font-weight: 500;
  color: #1e293b;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.staging-type {
  font-size: 10px;
  color: #64748b;
}

.staging-remove-btn {
  background: transparent;
  border: none;
  cursor: pointer;
  color: #94a3b8;
  display: flex;
  align-items: center;
  padding: 2px;
  border-radius: 50%;
  margin-left: 4px;
}

.staging-remove-btn:hover {
  color: #dc2626;
  background: #fee2e2;
}

/* 输入容器 */
.input-composer-box {
  background: #ffffff;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 10px 14px 8px;
  transition: all 0.15s ease;
}

.input-composer-box:focus-within {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.composer-textarea {
  width: 100%;
  border: none;
  outline: none;
  resize: none;
  font-size: 14px;
  line-height: 1.5;
  color: #0f172a;
  background: transparent;
  font-family: inherit;
}

.composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid #f1f5f9;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 4px;
  border: 1px solid var(--app-border);
  background: #f8fafc;
  color: #475569;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.tool-btn:hover:not(:disabled) {
  background: #eff6ff;
  color: #2563eb;
  border-color: #bfdbfe;
}

.tool-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.char-count {
  font-size: 12px;
  color: #94a3b8;
}

.send-btn {
  background: #2563eb;
  border: none;
  font-weight: 500;
  padding: 8px 18px;
  border-radius: 6px;
}

.send-btn:hover:not(:disabled) {
  background: #1d4ed8;
}

.footer-bottom-tip {
  margin-top: 8px;
  text-align: center;
  font-size: 11px;
  color: #94a3b8;
}
</style>
