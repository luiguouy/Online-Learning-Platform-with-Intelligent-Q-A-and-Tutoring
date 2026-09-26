<template>
  <el-drawer
    v-model="visible"
    title="知识点精解"
    direction="rtl"
    size="520px"
    class="knowledge-panel"
  >
    <div class="panel-head">
      <span class="panel-point">{{ pointName }}</span>
      <el-tag size="small" type="info" effect="plain">只读</el-tag>
    </div>

    <!-- 加载中：精解要等大模型整段生成，比 SSE 问答慢，必须有明确等待态 -->
    <div v-if="loading" class="panel-state">
      <span class="panel-spinner" />
      正在生成本知识点精解（整段生成，预计需要 30~60 秒）…
    </div>

    <!-- 失败：把原因原样展示（含"响应结构待确认"这类契约问题），不渲染空面板 -->
    <el-alert
      v-else-if="error"
      class="panel-error"
      type="error"
      :closable="false"
      show-icon
      :title="error"
    />

    <!-- 成功：复用 MarkdownViewer，与 AI 回答同一套渲染/净化规则，行为不会漂移 -->
    <div v-else class="panel-body">
      <MarkdownViewer :content="content" />
    </div>

    <template #footer>
      <div class="panel-footer">
        <span class="panel-tip">内容由大模型基于本课程课件生成，仅供理解参考</span>
        <el-button @click="visible = false">关闭</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 知识点精解面板（C2.5）
 *
 * 定位：一键触发知识点精解，以 Markdown 展示「核心概念定义 + 难点辨析」。
 * 严格只读 —— 不含自测题、不做答题交互（MEMBER_C_DEV_GUIDE 4.3 / 第一章第 5 条）。
 *
 * 请求放在面板内部（而不是让 ChatWorkspace 代发）：
 * 加载态 / 错误态 / 空态天然属于这块 UI，放一起才不会出现"父组件忘了复位 loading"这类漏洞。
 * 对外只暴露 open()，与 GroundingDrawer 的用法保持一致。
 */
import { ref } from 'vue';

import { generateKnowledgePoint } from '@/api/knowledge';
import MarkdownViewer from '@/components/MarkdownViewer.vue';

const visible = ref(false);
const loading = ref(false);
const error = ref('');
const content = ref('');
const pointName = ref('');

/**
 * 打开面板并生成精解。
 * 用递增令牌丢弃过期响应：用户快速点了两条不同回答的「知识点精解」时，
 * 只允许最后一次请求的结果上屏（先发的慢响应不许覆盖后发的）。
 */
let requestToken = 0;

async function open(courseId: number, name: string): Promise<void> {
  const token = ++requestToken;
  pointName.value = name;
  content.value = '';
  error.value = '';
  loading.value = true;
  visible.value = true;

  try {
    const markdown = await generateKnowledgePoint({ courseId, knowledgePoint: name });
    if (token !== requestToken) return;
    content.value = markdown;
  } catch (err) {
    if (token !== requestToken) return;
    error.value = err instanceof Error ? err.message : '知识点精解生成失败，请稍后重试';
  } finally {
    if (token === requestToken) {
      loading.value = false;
    }
  }
}

defineExpose({ open });
</script>

<style scoped>
.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--app-border);
}

.panel-point {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text);
  word-break: break-word;
}

.panel-state {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 28px 0;
  font-size: 13px;
  color: var(--app-text-muted);
}

/* 纯 CSS 旋转指示器：不引额外组件，避免为一个加载态增大分包 */
.panel-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--el-color-primary-light-7);
  border-top-color: var(--el-color-primary);
  border-radius: 50%;
  animation: panel-spin 0.8s linear infinite;
}

@keyframes panel-spin {
  to {
    transform: rotate(360deg);
  }
}

.panel-body {
  font-size: 14px;
  line-height: 1.8;
  color: var(--app-text);
}

.panel-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-tip {
  font-size: 12px;
  color: var(--app-text-muted);
  text-align: left;
}
</style>
