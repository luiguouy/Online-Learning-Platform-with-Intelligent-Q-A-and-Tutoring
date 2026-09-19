<template>
  <div class="doc-manage">
    <div class="page-head">
      <div>
        <h2 class="page-title">课程知识库课件管理</h2>
        <p class="page-desc">
          上传 PDF / Markdown 课件，系统将自动调用 LangChain4j 进行智能解析、切块与向量嵌入
        </p>
      </div>
      <div class="page-actions">
        <el-button :icon="Refresh" :loading="loading" @click="refreshManually">刷新</el-button>
        <el-button type="primary" :icon="Upload" :disabled="currentCourseId === null" @click="openUpload">
          上传新课件资料
        </el-button>
      </div>
    </div>

    <!-- Q17：普通课件 5~20 秒，大课件可能 1~2 分钟；超过上限后停止自动轮询，转为手动刷新兜底 -->
    <el-alert
      v-if="pollExpired"
      class="poll-alert"
      type="warning"
      show-icon
      :closable="false"
      title="已停止自动刷新（切块超过 2 分钟）"
      description="课件仍在后台切块中，请点「刷新」查看最新状态。"
    />

    <el-table v-loading="loading" :data="docList" stripe border class="doc-table">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="fileName" label="课件文件名" min-width="220" show-overflow-tooltip />
      <el-table-column prop="fileType" label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small">{{ String(row.fileType).toUpperCase() }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="切块片段数" width="120" />
      <el-table-column prop="parseStatus" label="知识库状态" width="180">
        <template #default="{ row }">
          <el-tag v-if="row.parseStatus === 'CHUNKED'" type="success">已就绪 (RAG可用)</el-tag>
          <el-tag v-else-if="row.parseStatus === 'PARSING'" type="warning">切块向量化中...</el-tag>
          <el-tag v-else-if="row.parseStatus === 'PENDING'" type="info">待处理</el-tag>
          <el-tooltip v-else :content="row.errorMsg || '切块失败'" placement="top">
            <el-tag type="danger">失败 (查看原因)</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="上传时间" width="180" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <!-- 后端 reindex 无状态机守卫，对 PARSING/PENDING 中的课件再点会并发两次切块产生重复切片，故禁用 -->
          <el-button
            link
            type="primary"
            size="small"
            :disabled="row.parseStatus === 'PARSING' || row.parseStatus === 'PENDING'"
            @click="handleReindex(row)"
          >
            重建索引
          </el-button>
          <el-button link type="danger" size="small" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <DocUploadModal
      v-model="uploadDialogVisible"
      :course-id="currentCourseId"
      @uploaded="handleUploaded"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 课件管理页（D1.3 表格 + D1.4 上传入口；D2.1 起走真实接口）
 *
 * 状态机四态 PENDING / PARSING / CHUNKED / FAILED 命名已冻结，禁止别名
 * （DEV_SPECIFICATION.md 4.2）。
 * 轮询：仅当列表中存在未完成的课件时才 3 秒轮询一次（D2.2）。
 * Q17 兜底：单轮自动轮询最长 2 分钟（普通课件 5~20 秒，大课件 1~2 分钟），
 * 超时即停止并展示提示条，改由页面「刷新」按钮手动兜底。
 * D2.4：操作列「重建索引」→ 后端清旧向量并异步重切块（状态回 PARSING），由上述轮询接管。
 */
import { computed, onUnmounted, ref, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { Refresh, Upload } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';

import { deleteDoc, fetchDocList, reindexDoc } from '@/api/teacher';
import { useCourseStore } from '@/stores/course';
import DocUploadModal from './components/DocUploadModal.vue';
import type { CourseDoc } from '@/types';

/** 轮询间隔固定 3 秒 */
const POLL_INTERVAL_MS = 3000;
/** Q17：单轮自动轮询的上限 2 分钟，超过则停止自动刷新，改为手动兜底 */
const POLL_MAX_DURATION_MS = 120_000;

const courseStore = useCourseStore();
const { currentCourseId } = storeToRefs(courseStore);

const docList = ref<CourseDoc[]>([]);
const loading = ref(false);
const uploadDialogVisible = ref(false);

let pollTimer: number | null = null;
/** 组件是否已卸载：卸载后在途轮询请求回来后不得再重新拉起 interval */
let unmounted = false;
/** 本轮自动轮询的截止时间戳 */
let pollDeadline = 0;
/** 是否已因超时停止自动轮询（用于渲染提示条） */
const pollExpired = ref(false);

const hasPendingTask = computed(() =>
  docList.value.some(
    (doc) => doc.parseStatus === 'PENDING' || doc.parseStatus === 'PARSING',
  ),
);

/**
 * 拉取课件列表。
 * @param silent 轮询刷新传 true：不触发 loading 遮罩、不弹错误提示，
 *               避免表格每 3 秒闪一次遮罩（PR #27 评审）；失败静默，等下次轮询或手动刷新。
 */
async function loadDocs(silent = false): Promise<void> {
  const courseId = currentCourseId.value;
  if (courseId === null) return;

  if (!silent) loading.value = true;
  try {
    docList.value = await fetchDocList(courseId);
    syncPolling();
  } catch {
    if (!silent) ElMessage.error('课件列表加载失败');
  } finally {
    if (!silent) loading.value = false;
  }
}

/** 启动自动轮询，并重置本轮 2 分钟计时窗口 */
function startPolling(): void {
  if (unmounted || pollTimer !== null) return;
  pollDeadline = Date.now() + POLL_MAX_DURATION_MS;
  pollExpired.value = false;
  pollTimer = window.setInterval(() => void loadDocs(true), POLL_INTERVAL_MS);
}

function stopPolling(): void {
  if (pollTimer === null) return;
  window.clearInterval(pollTimer);
  pollTimer = null;
}

/**
 * 只在存在未完成课件时轮询，避免无意义的空转请求。
 * Q17：超过 2 分钟仍未就绪 → 停止自动轮询并置 pollExpired，交给手动刷新兜底。
 */
function syncPolling(): void {
  if (!hasPendingTask.value) {
    stopPolling();
    pollExpired.value = false;
    return;
  }

  if (pollTimer !== null && Date.now() >= pollDeadline) {
    stopPolling();
    pollExpired.value = true;
    ElMessage.warning('切块耗时超过 2 分钟，已停止自动刷新，请点「刷新」查看最新状态');
    return;
  }

  if (pollTimer === null && !pollExpired.value) {
    startPolling();
  }
}

/** 手动刷新：重置 2 分钟窗口后重新拉取列表 */
async function refreshManually(): Promise<void> {
  pollExpired.value = false;
  await loadDocs();
}

function openUpload(): void {
  uploadDialogVisible.value = true;
}

function handleUploaded(): void {
  // 上传成功后必须重置窗口，否则超时状态会一直挡住自动轮询
  void refreshManually();
}

/**
 * 重建索引（D2.4）：后端先清旧向量再**异步**重新切块，接口返回 true 只代表任务已受理。
 * 必须走 refreshManually() 重置 2 分钟轮询窗口 —— 否则若此前已 pollExpired，
 * 新任务不会被自动轮询捕捉（与上传成功后的处理同理）。
 */
async function handleReindex(row: CourseDoc): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将清除「${row.fileName}」的现有向量切片并重新切块，期间该课件暂时检索不到，确定继续？`,
      '确认重建索引',
      { type: 'warning' },
    );
  } catch {
    return;
  }

  try {
    await reindexDoc(row.id);
    ElMessage.success('已提交重建索引，正在重新切块…');
    await refreshManually();
  } catch {
    ElMessage.error('重建索引失败');
  }
}

/** 删除课件：后端会同步级联清除该课件在 Chroma 中的全部向量切片 */
async function handleDelete(id: number): Promise<void> {
  try {
    await ElMessageBox.confirm('删除后该课件的向量切片会被同步清除，确定删除？', '确认删除', {
      type: 'warning',
    });
  } catch {
    return;
  }

  try {
    await deleteDoc(id);
    ElMessage.success('课件已删除');
    await loadDocs();
  } catch {
    ElMessage.error('删除失败');
  }
}

// 切换课程后重新拉取，保证表格不串课
watch(currentCourseId, () => loadDocs(), { immediate: true });

onUnmounted(() => {
  unmounted = true;
  stopPolling();
});
</script>

<style scoped>
.doc-manage {
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

.page-actions {
  display: flex;
  flex-shrink: 0;
  gap: 10px;
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

.poll-alert {
  margin-bottom: 14px;
}

.doc-table {
  width: 100%;
}
</style>
