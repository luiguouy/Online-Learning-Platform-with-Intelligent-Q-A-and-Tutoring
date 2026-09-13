<template>
  <div class="doc-manage">
    <div class="page-head">
      <div>
        <h2 class="page-title">课程知识库课件管理</h2>
        <p class="page-desc">
          上传 PDF / Markdown 课件，系统将自动调用 LangChain4j 进行智能解析、切块与向量嵌入
        </p>
      </div>
      <el-button type="primary" :icon="Upload" :disabled="currentCourseId === null" @click="openUpload">
        上传新课件资料
      </el-button>
    </div>

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
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
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
 * 课件管理页（D1.3 表格 + Mock 渲染；D1.4 上传入口）
 *
 * 状态机四态 PENDING / PARSING / CHUNKED / FAILED 命名已冻结，禁止别名
 * （DEV_SPECIFICATION.md 4.2）。
 * 轮询：仅当列表中存在未完成的课件时才 3 秒轮询一次（D2.2 要求，此处提前落地骨架）。
 */
import { computed, onUnmounted, ref, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { Upload } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';

import { deleteDoc, fetchDocList } from '@/api/teacher';
import { useCourseStore } from '@/stores/course';
import DocUploadModal from './components/DocUploadModal.vue';
import type { CourseDoc } from '@/types';

/** 轮询间隔固定 3 秒 */
const POLL_INTERVAL_MS = 3000;

const courseStore = useCourseStore();
const { currentCourseId } = storeToRefs(courseStore);

const docList = ref<CourseDoc[]>([]);
const loading = ref(false);
const uploadDialogVisible = ref(false);

let pollTimer: number | null = null;

const hasPendingTask = computed(() =>
  docList.value.some(
    (doc) => doc.parseStatus === 'PENDING' || doc.parseStatus === 'PARSING',
  ),
);

async function loadDocs(): Promise<void> {
  const courseId = currentCourseId.value;
  if (courseId === null) return;

  loading.value = true;
  try {
    docList.value = await fetchDocList(courseId);
    syncPolling();
  } catch {
    ElMessage.error('课件列表加载失败');
  } finally {
    loading.value = false;
  }
}

/** 只在存在未完成课件时轮询，避免无意义的空转请求 */
function syncPolling(): void {
  if (hasPendingTask.value) {
    if (pollTimer === null) {
      pollTimer = window.setInterval(loadDocs, POLL_INTERVAL_MS);
    }
    return;
  }
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
}

function openUpload(): void {
  uploadDialogVisible.value = true;
}

function handleUploaded(): void {
  loadDocs();
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
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
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

.doc-table {
  width: 100%;
}
</style>
