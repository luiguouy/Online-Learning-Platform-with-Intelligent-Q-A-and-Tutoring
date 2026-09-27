<template>
  <el-drawer
    v-model="visible"
    title="课件出处知识库溯源"
    direction="rtl"
    size="380px"
    class="grounding-drawer"
  >
    <div v-if="references.length === 0" class="drawer-empty">
      本次回答未引用课件，或暂无相关参考切块
    </div>

    <div v-else class="ref-list">
      <div v-for="(item, index) in references" :key="`${item.docId}-${item.chunkIndex}-${index}`" class="ref-card">
        <div class="ref-head">
          <span class="ref-badge">[引用 {{ index + 1 }}]</span>
          <span class="ref-score">相关度 {{ formatScore(item.score) }}</span>
        </div>

        <div class="ref-file" :title="item.fileName">
          <span class="ref-file-icon">DOC</span>
          <span class="ref-file-name">{{ item.fileName }}</span>
        </div>

        <div class="ref-meta">第 {{ item.chunkIndex }} 块 · 课件 ID {{ item.docId }}</div>

        <p
          class="ref-snippet"
          :class="{ expanded: expandedIndex === index }"
          @click="toggle(index)"
        >
          {{ item.snippet }}
        </p>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 课件出处溯源抽屉（C2.3）
 *
 * 什么时候出现：SSE 首包 `references` 到达时，聊天气泡上出现「参考资料 (N 处)」徽章，
 * 点它打开本抽屉。首包没带引用（或历史记录 groundingReferences 为 null）时不显示入口。
 *
 * 展示字段严格对齐契约 4.2 的 SseReference 五字段：
 * fileName（课件名）/ chunkIndex（分块号）/ score（相关度）/ snippet（原文片段）/ docId（课件 ID）。
 */
import { ref } from 'vue';

import type { SseReference } from '@/types';

const visible = ref(false);
const references = ref<SseReference[]>([]);
/** 当前被点开的片段下标（null = 都处于折叠态） */
const expandedIndex = ref<number | null>(null);

/** 相似度分数转百分比：契约里是 0~1 的小数 */
function formatScore(score: number): string {
  if (typeof score !== 'number' || Number.isNaN(score)) return '—';
  return `${(score * 100).toFixed(1)}%`;
}

function toggle(index: number): void {
  expandedIndex.value = expandedIndex.value === index ? null : index;
}

/** 打开抽屉并载入某条回答的出处（由 ChatWorkspace 调用） */
function open(list: SseReference[]): void {
  references.value = list;
  expandedIndex.value = null;
  visible.value = true;
}

defineExpose({ open });
</script>

<style scoped>
.drawer-empty {
  padding: 32px 0;
  font-size: 13px;
  color: var(--app-text-muted);
  text-align: center;
}

.ref-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ref-card {
  padding: 12px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: #f8fafc;
  transition:
    border-color 0.2s,
    background-color 0.2s;
}

.ref-card:hover {
  border-color: var(--el-color-primary-light-5);
  background: #ffffff;
}

.ref-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.ref-badge {
  padding: 1px 8px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 4px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 600;
}

.ref-score {
  color: var(--app-text-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.ref-file {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.ref-file-icon {
  flex-shrink: 0;
  padding: 1px 4px;
  border-radius: 3px;
  background: #e2e8f0;
  color: #475569;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.4px;
}

.ref-file-name {
  overflow: hidden;
  color: var(--app-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ref-meta {
  margin: 4px 0 8px;
  color: var(--app-text-muted);
  font-size: 11px;
}

.ref-snippet {
  margin: 0;
  padding: 8px 10px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  background: #ffffff;
  color: #475569;
  font-size: 12px;
  line-height: 1.65;
  cursor: pointer;
  /* 默认收 3 行，点一下展开全文（长切块不撑爆抽屉） */
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}

.ref-snippet.expanded {
  display: block;
  -webkit-line-clamp: unset;
  overflow: visible;
}
</style>
