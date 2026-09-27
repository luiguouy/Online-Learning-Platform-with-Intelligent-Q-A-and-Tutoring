<template>
  <!--
    统一三态占位（C3.1）
    为什么要有它：加载中 / 空 / 失败 是每个异步区块都要处理的三件事。
    分散在各视图里手写，会出现「某处忘了复位 loading」「某处失败后留白」这类漏项，
    也正是「断网时白屏」的成因。收成一个组件后，三态只有一个实现，
    且失败态**必然**带重试入口 —— 不会出现「像坏了但没出路」的界面。
  -->
  <div class="state-placeholder">
    <!-- 加载中：骨架屏。比一句「加载中…」更能说明"这里将有内容" -->
    <el-skeleton v-if="state === 'loading'" animated :rows="rows" />

    <!-- 失败：友好文案 + 重试（retryable 默认开） -->
    <div v-else-if="state === 'error'" class="state-error">
      <el-icon class="state-error-icon"><WarningFilled /></el-icon>
      <p class="state-error-title">{{ errorTitle }}</p>
      <p v-if="description" class="state-error-desc">{{ description }}</p>
      <el-button v-if="retryable" type="primary" plain size="small" @click="emit('retry')">
        重试
      </el-button>
    </div>

    <!-- 空：交给 el-empty，与 Element Plus 观感一致（不自定义插图/圆角） -->
    <el-empty v-else :description="description" :image-size="imageSize" />
  </div>
</template>

<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue';

/**
 * 异步区块三态占位。
 *
 * 用法：`<StatePlaceholder v-if="loading" state="loading" />` 等，由调用方决定何时渲染哪一态
 * （而不是把状态判断也塞进来 —— 各视图的判空条件不一样）。
 */
withDefaults(
  defineProps<{
    /** loading = 骨架屏；error = 友好错误 + 重试；empty = 空态 */
    state: 'loading' | 'error' | 'empty';
    /** 空态 / 错误态的说明文案 */
    description?: string;
    /** 错误态标题（默认「加载失败」） */
    errorTitle?: string;
    /** 骨架屏行数 */
    rows?: number;
    /** 空态插图尺寸 */
    imageSize?: number;
    /** 错误态是否显示重试按钮 */
    retryable?: boolean;
  }>(),
  {
    description: '',
    errorTitle: '加载失败',
    rows: 3,
    imageSize: 80,
    retryable: true,
  },
);

const emit = defineEmits<{ retry: [] }>();
</script>

<style scoped>
/* 只做间距与排布；(Q19) 不为圆角 / 阴影 / 间距刻度外的值自行设值 */
.state-placeholder {
  padding: 16px;
}

.state-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 8px;
  text-align: center;
}

.state-error-icon {
  font-size: 28px;
  color: var(--el-color-warning);
}

.state-error-title {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--app-text, var(--el-text-color-primary));
}

.state-error-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
