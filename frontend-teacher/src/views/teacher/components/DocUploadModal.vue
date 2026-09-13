<template>
  <el-dialog
    v-model="visible"
    title="上传课程资料"
    width="520px"
    :close-on-click-modal="false"
    @closed="resetState"
  >
    <el-upload
      drag
      v-bind="uploadBindings"
      :before-upload="beforeUpload"
      :on-progress="handleProgress"
      :on-success="handleSuccess"
      :on-error="handleError"
      :show-file-list="false"
      accept=".pdf,.docx,.md,.txt"
    >
      <el-icon class="upload-icon"><UploadFilled /></el-icon>
      <div class="upload-text">拖拽课件文件到此处，或 <em>点击上传</em></div>
      <template #tip>
        <div class="upload-tip">支持 PDF / DOCX / Markdown / TXT，单文件大小不超过 50MB</div>
      </template>
    </el-upload>

    <div v-if="currentFileName" class="upload-progress">
      <div class="progress-name">{{ currentFileName }}</div>
      <el-progress :percentage="uploadPercent" :status="progressStatus" />
    </div>

    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 课件拖拽上传弹窗（D1.4）
 *
 * 两种通道：
 * - Mock 阶段（Week 1）：不真正请求后端，仅本地模拟进度与状态流转
 * - 真实阶段（D2.1 起）：走 action + Authorization 头
 *   ⚠️ el-upload 使用自身上传通道，不经过 axios 拦截器，因此必须手动补鉴权头，
 *      且头值必须带 "Bearer " 前缀（DEV_SPECIFICATION.md 4.2）
 */
import { computed, ref } from 'vue';
import { UploadFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { UploadProgressEvent, UploadProps, UploadRequestOptions } from 'element-plus';

import { USE_MOCK } from '@/config';
import { mockCreateDoc } from '@/mock/teacherDocs';

/** 上传接口路径（成员 B 提供，禁止改写） */
const UPLOAD_ACTION = '/api/teacher/docs/upload';

/** 单文件大小上限 50MB */
const MAX_FILE_SIZE_MB = 50;

/**
 * 允许的扩展名（大小写不敏感）。
 * accept 只能约束点击文件选择器，Element Plus 的拖拽通道不做类型过滤，
 * 必须在 beforeUpload 里兜底校验（PR #27 评审）。
 */
const ALLOWED_EXTENSIONS = ['pdf', 'docx', 'md', 'txt'];

const props = defineProps<{ courseId: number | null }>();
const emit = defineEmits<{ (e: 'uploaded'): void }>();
const visible = defineModel<boolean>({ required: true });

const currentFileName = ref('');
const uploadPercent = ref(0);

// el-upload 不走 axios 拦截器，必须手动补鉴权头
const uploadHeaders = computed<Record<string, string>>(() => {
  const token = localStorage.getItem('satoken') || '';
  return { Authorization: `Bearer ${token}` };
});

const progressStatus = computed<'success' | undefined>(() =>
  uploadPercent.value >= 100 ? 'success' : undefined,
);

/** Mock 上传：仅本地模拟进度，完成后写入 Mock 数据（PARSING → 由 Mock 模块自动转 CHUNKED） */
const mockUpload = (options: UploadRequestOptions): Promise<void> =>
  new Promise<void>((resolve) => {
    uploadPercent.value = 0;
    const timer = window.setInterval(() => {
      uploadPercent.value = Math.min(100, uploadPercent.value + 10);
      if (uploadPercent.value < 100) return;

      window.clearInterval(timer);
      if (props.courseId !== null && currentFileName.value) {
        mockCreateDoc(props.courseId, currentFileName.value);
      }
      options.onSuccess({ code: 200, message: 'Mock 上传成功' });
      resolve();
    }, 120);
  });

const uploadBindings = computed<Partial<UploadProps>>(() => {
  if (USE_MOCK) {
    return { httpRequest: mockUpload };
  }
  return {
    action: UPLOAD_ACTION,
    headers: uploadHeaders.value,
    data: { courseId: props.courseId ?? 0 },
  };
});

function beforeUpload(file: File): boolean {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
  if (!ALLOWED_EXTENSIONS.includes(extension)) {
    ElMessage.error('仅支持 PDF / DOCX / Markdown / TXT 格式的文件');
    return false;
  }

  const isLt50M = file.size / 1024 / 1024 < MAX_FILE_SIZE_MB;
  if (!isLt50M) {
    ElMessage.error(`上传文件大小不能超过 ${MAX_FILE_SIZE_MB}MB!`);
    return false;
  }
  currentFileName.value = file.name;
  uploadPercent.value = 0;
  return true;
}

function handleProgress(evt: UploadProgressEvent): void {
  uploadPercent.value = Math.round(evt.percent ?? 0);
}

function handleSuccess(response: unknown): void {
  const result = response as { code?: number; message?: string } | undefined;
  if (result && result.code !== 200) {
    ElMessage.error(result.message || '上传失败');
    return;
  }
  ElMessage.success('上传成功，后台已启动向量切片索引！');
  emit('uploaded');
  visible.value = false;
}

function handleError(): void {
  ElMessage.error('上传失败，请重试');
}

function resetState(): void {
  currentFileName.value = '';
  uploadPercent.value = 0;
}
</script>

<style scoped>
.upload-icon {
  font-size: 52px;
  color: #c0c4cc;
}

.upload-text {
  margin-top: 8px;
  font-size: 14px;
  color: var(--app-text-muted);
}

.upload-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #a8abb2;
}

.upload-progress {
  margin-top: 16px;
}

.progress-name {
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--app-text-muted);
  word-break: break-all;
}
</style>
