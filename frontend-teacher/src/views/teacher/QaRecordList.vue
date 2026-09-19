<template>
  <div class="qa-records">
    <div class="page-head">
      <div>
        <h2 class="page-title">学生问答记录</h2>
        <p class="page-desc">
          按课程查看学生提问明细，可展开查看每条回答命中的课件出处。本页<b>只读</b>，不提供修改 AI 回答的入口
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </div>

    <div class="filter-bar">
      <el-input
        v-model="keyword"
        class="keyword-input"
        placeholder="按提问或回答关键词搜索"
        clearable
        @keyup.enter="search"
        @clear="search"
      />
      <el-button type="primary" :icon="Search" @click="search">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="records" stripe border class="qa-table">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="question" label="学生提问" min-width="200" show-overflow-tooltip />
      <el-table-column label="AI 回答" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="answer-text">{{ plainAnswer(row.answer) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="参考出处" width="110">
        <template #default="{ row }">
          <el-button link type="primary" @click="showRefs(row)">
            查看 ({{ parseRefs(row.groundingReferences).length }})
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="提问时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="学生反馈" width="95">
        <template #default="{ row }">
          <el-tag v-if="row.feedbackRating === 1" type="success" size="small">点赞</el-tag>
          <el-tag v-else-if="row.feedbackRating === -1" type="danger" size="small">点踩</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > 0"
      v-model:current-page="pageNum"
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="PAGE_SIZE"
      @current-change="handlePageChange"
    />

    <el-drawer v-model="refsVisible" title="参考出处" size="440px">
      <el-empty v-if="currentRefs.length === 0" description="该条回答未命中课件片段" />
      <div v-for="(ref, i) in currentRefs" :key="i" class="ref-item">
        <div class="ref-title">{{ ref.fileName }} · 第 {{ ref.chunkIndex }} 段</div>
        <div class="ref-score">相关度 {{ ref.score }}</div>
        <p class="ref-snippet">{{ ref.snippet }}</p>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * 问答记录查看页（D2.3，Issue #23）
 *
 * 只读页：仅「按课程查看 + 关键词搜索 + 分页 + 展开参考出处」，
 * 不提供任何修改 AI 回答的入口（人工纠偏不在本期范围，数据库也无对应字段）。
 *
 * 接口：GET /api/teacher/qa/records?courseId=&pageNum=&pageSize=&keyword=
 * ⚠️ 返回 IPage（records/total/size/current/pages），与课件列表的裸数组结构不同
 *    （2026-09-19 实测确认）。
 *
 * 偏离说明：MEMBER_D_DEV_GUIDE.md 4.2 的模板把「课程下拉」放在本页内；
 * 本工程把课程选择器放在 TeacherLayout 顶栏（全局 useCourseStore.currentCourseId），
 * 故本页不再重复放一个，改为跟随顶栏切换自动重查 —— 与 CourseDocManage.vue 一致。
 */
import { ref, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { Refresh, Search } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';

import { fetchQaRecords } from '@/api/teacher';
import { useCourseStore } from '@/stores/course';
import type { QaRecord, SseReference } from '@/types';

/** 分页大小固定 10（后端 pageSize 默认值一致） */
const PAGE_SIZE = 10;

const courseStore = useCourseStore();
const { currentCourseId } = storeToRefs(courseStore);

const records = ref<QaRecord[]>([]);
const total = ref(0);
const pageNum = ref(1);
const keyword = ref('');
const loading = ref(false);

const refsVisible = ref(false);
const currentRefs = ref<SseReference[]>([]);

/**
 * 参考出处解析。
 * 后端 `groundingReferences` 实测为**数组**（已由 JacksonTypeHandler 反序列化），
 * string 分支仅作兼容保护 —— Q7~Q13 已确认不会命中。
 */
function parseRefs(raw: QaRecord['groundingReferences']): SseReference[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw;
  try {
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as SseReference[]) : [];
  } catch {
    return [];
  }
}

/**
 * AI 回答是 Markdown 文本，列表列里转成单行纯文本摘要。
 * 刻意不引 Markdown 渲染库：依赖白名单之外，且 v-html 有 XSS 面。
 */
function plainAnswer(answer: string): string {
  return (answer || '')
    .replace(/```[\s\S]*?```/g, '〔代码块〕')
    .replace(/^#{1,6}\s*/gm, '')
    .replace(/\*\*(.+?)\*\*/g, '$1')
    .replace(/\*(.+?)\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/^\s*[-*+]\s+/gm, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 后端 createdAt 为 ISO 格式（如 2026-09-17T09:44:47），转成易读的「日期 时间」 */
function formatTime(value: string): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}

async function loadRecords(): Promise<void> {
  const courseId = currentCourseId.value;
  if (courseId === null) {
    records.value = [];
    total.value = 0;
    return;
  }

  loading.value = true;
  try {
    const page = await fetchQaRecords({
      courseId,
      pageNum: pageNum.value,
      pageSize: PAGE_SIZE,
      keyword: keyword.value.trim(),
    });
    records.value = page.records ?? [];
    total.value = page.total ?? 0;
  } catch {
    ElMessage.error('问答记录加载失败');
  } finally {
    loading.value = false;
  }
}

/** 查询：回到第 1 页再拉取（关键词变化后旧页码可能越界） */
function search(): void {
  pageNum.value = 1;
  void loadRecords();
}

function reload(): void {
  void loadRecords();
}

function handlePageChange(): void {
  void loadRecords();
}

function showRefs(row: QaRecord): void {
  currentRefs.value = parseRefs(row.groundingReferences);
  refsVisible.value = true;
}

// 顶栏切换课程后回到第 1 页重查，保证表格不串课（与 CourseDocManage 同策略）
watch(
  currentCourseId,
  () => {
    pageNum.value = 1;
    void loadRecords();
  },
  { immediate: true },
);
</script>

<style scoped>
.qa-records {
  padding: 20px;
  background-color: #ffffff;
  border-radius: 8px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
}

.page-title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--app-text);
}

.page-desc {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--app-text-muted);
}

.page-actions {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.keyword-input {
  width: 300px;
}

.qa-table {
  width: 100%;
}

.answer-text {
  color: var(--app-text-muted);
}

.muted {
  color: var(--app-text-muted);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.ref-item {
  padding: 12px;
  margin-bottom: 12px;
  background-color: var(--app-bg);
  border-radius: 6px;
}

.ref-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--app-text);
}

.ref-score {
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-text-muted);
}

.ref-snippet {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text);
}
</style>
