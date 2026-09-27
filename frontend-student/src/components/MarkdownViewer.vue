<template>
  <div ref="rootRef" class="markdown-body" v-html="sanitizedHtml" />
</template>

<script setup lang="ts">
/**
 * Markdown 与代码高亮清洗渲染器（C2.2）
 *
 * 渲染与净化规则全部在 utils/markdown.ts（独立模块，可被自测直接引用）；
 * 本组件只负责三件事：`v-html` 上屏、给代码块挂「复制」按钮、排版样式。
 *
 * 复制按钮走 DOM 后处理而不是 v-html 里塞内联 onclick：
 * 内联事件会被 DOMPurify 白名单清掉，而且每次重新渲染后按钮会跟着重建，不用维护状态。
 *
 * 不做数学公式渲染（不引 KaTeX/MathJax，已超出冻结范围 —— MEMBER_C_DEV_GUIDE 第一节）。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

// 深色代码块配深色主题：github.css 是浅色主题，token 配色按白底设计，
// 直接铺在深色块上关键字会看不清（实测对比度极低）。与其换浅色块，不如换主题。
import 'highlight.js/styles/github-dark.css';

import { renderMarkdownSafely } from '@/utils/markdown';

const props = defineProps<{
  content: string;
}>();

const rootRef = ref<HTMLDivElement>();

/** 渲染 → 净化。computed 按 content 缓存，同一帧内多次读取不会重复渲染 */
const sanitizedHtml = computed(() => renderMarkdownSafely(props.content));

/** 复制按钮的定时复位句柄，组件卸载时清掉 */
let resetTimer: number | null = null;

/**
 * 给每个代码块挂「复制」按钮。
 * 放在 DOM 后处理而不是 v-html 里：内联 onclick 会被 DOMPurify 清掉，
 * 且每次重新渲染后按钮会自动跟着重建，不需要额外维护状态。
 */
function decorateCodeBlocks(): void {
  const root = rootRef.value;
  if (!root) return;
  root.querySelectorAll('pre').forEach((pre) => {
    if (pre.querySelector('.code-copy')) return; // 已挂过，不重复挂
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'code-copy';
    button.textContent = '复制';
    button.addEventListener('click', () => {
      void copyCode(pre, button);
    });
    pre.appendChild(button);
  });
}

async function copyCode(pre: Element, button: HTMLButtonElement): Promise<void> {
  const code = pre.querySelector('code')?.textContent ?? '';
  try {
    await navigator.clipboard.writeText(code);
    button.textContent = '已复制';
  } catch {
    // 非安全上下文或用户拒绝授权：如实提示，不吞掉
    button.textContent = '复制失败';
  }
  if (resetTimer !== null) window.clearTimeout(resetTimer);
  resetTimer = window.setTimeout(() => {
    button.textContent = '复制';
    resetTimer = null;
  }, 1500);
}

// 流式期间 content 每帧变一次，渲染后立刻补按钮（按钮存在时是空操作）
watch(
  sanitizedHtml,
  () => {
    void nextTick(decorateCodeBlocks);
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  if (resetTimer !== null) {
    window.clearTimeout(resetTimer);
    resetTimer = null;
  }
});
</script>

<style scoped>
/* 只做排版微调；配色/间距沿用项目变量，不引 Tailwind */
.markdown-body {
  font-size: 14px;
  line-height: 1.75;
  color: var(--app-text);
  word-break: break-word;
}

.markdown-body :deep(> *:first-child) {
  margin-top: 0;
}

.markdown-body :deep(> *:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 14px 0 8px;
  font-weight: 600;
  line-height: 1.4;
}

.markdown-body :deep(h1) {
  font-size: 18px;
}

.markdown-body :deep(h2) {
  font-size: 16px;
}

.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  font-size: 15px;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 8px 0;
  padding-left: 22px;
}

.markdown-body :deep(li) {
  margin: 4px 0;
}

.markdown-body :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--app-text-muted);
}

/* 行内代码 */
.markdown-body :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f1f5f9;
  font-family: 'JetBrains Mono', Consolas, Monaco, monospace;
  font-size: 13px;
}

/* 代码块：外层 pre 承载深色底 + 复制按钮定位 */
.markdown-body :deep(pre) {
  position: relative;
  margin: 10px 0;
  padding: 12px 14px;
  border-radius: 8px;
  background: #0f172a;
  overflow-x: auto;
}

.markdown-body :deep(pre code) {
  padding: 0;
  background: transparent;
  color: #e2e8f0;
  font-size: 12.5px;
  line-height: 1.6;
}

.markdown-body :deep(pre code.hljs) {
  background: transparent;
  padding: 0;
}

.markdown-body :deep(.code-copy) {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 2px 8px;
  border: 1px solid rgba(226, 232, 240, 0.35);
  border-radius: 4px;
  background: rgba(15, 23, 42, 0.75);
  color: #e2e8f0;
  font-size: 11px;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s;
}

.markdown-body :deep(pre:hover .code-copy),
.markdown-body :deep(.code-copy:focus-visible) {
  opacity: 1;
}

/* 表格 */
.markdown-body :deep(table) {
  width: 100%;
  margin: 10px 0;
  border-collapse: collapse;
  font-size: 13px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--app-border);
  text-align: left;
}

.markdown-body :deep(th) {
  background: #f8fafc;
  font-weight: 600;
}

.markdown-body :deep(a) {
  color: var(--el-color-primary);
  text-decoration: none;
}

.markdown-body :deep(a:hover) {
  text-decoration: underline;
}

.markdown-body :deep(hr) {
  margin: 12px 0;
  border: none;
  border-top: 1px solid var(--app-border);
}

.markdown-body :deep(img) {
  max-width: 100%;
}
</style>
