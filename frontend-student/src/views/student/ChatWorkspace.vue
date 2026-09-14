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
    <el-scrollbar class="chat-body">
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
              <!-- Week 1 先按纯文本渲染；Markdown + 代码高亮在 C2.2 接入 MarkdownViewer -->
              <div class="bubble-text">{{ message.content }}</div>
            </div>

            <!-- 参考资料入口：抽屉展示属 C2.3，本周只显示命中数量 -->
            <div
              v-if="message.role === 'ai' && (message.references?.length ?? 0) > 0"
              class="reference-bar"
            >
              <el-tag size="small" type="info" effect="plain">
                参考资料 ({{ message.references?.length }} 处)
              </el-tag>
            </div>
          </div>
        </div>
      </div>
    </el-scrollbar>

    <!-- 输入区：打字机流式属 C2.1，本周只落布局 -->
    <footer class="chat-footer">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="3"
        resize="none"
        disabled
        placeholder="流式问答将在第 2 周（C2.1）接入 SSE 后可用"
      />
      <div class="footer-actions">
        <span class="footer-tip">本周为布局骨架：SSE 打字机（C2.1）、Markdown 渲染（C2.2）、出处抽屉（C2.3）在第 2 周接入</span>
        <el-button type="primary" disabled>发送</el-button>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
/**
 * 智能答疑工作台（C1.5）
 *
 * 本周范围：页面结构完整（顶部课程信息 + 消息区 + 输入区），数据来自 Mock 历史问答。
 * 【不在本周范围】打字机流式（C2.1）、Markdown/代码高亮（C2.2）、出处抽屉（C2.3）、
 * 点赞点踩（C2.6）——输入区本周为 disabled 占位，不做假交互。
 */
import { ref } from 'vue';

import { useChatStore } from '@/stores/chatStore';
import { useCourseStore } from '@/stores/courseStore';

const courseStore = useCourseStore();
const chatStore = useChatStore();

/** 输入草稿：本周禁用了输入框，保留字段供 C2.1 直接接管 */
const draft = ref('');
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

.reference-bar {
  margin-top: 6px;
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
</style>
