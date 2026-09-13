<template>
  <div class="student-layout">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <span class="brand-title">智能答疑</span>
        <span class="brand-sub">学生工作台</span>
      </div>

      <!-- 课程切换：切换检索范围（courseId），学生可见全部课程 -->
      <div class="course-switch">
        <el-select
          v-model="courseStore.currentCourseId"
          :loading="courseStore.courses.length === 0"
          placeholder="请选择课程"
          class="course-select"
          @change="handleCourseChange"
        >
          <el-option
            v-for="course in courseStore.courses"
            :key="course.id"
            :label="course.courseName"
            :value="course.id"
          />
        </el-select>
      </div>

      <!-- 历史会话栏 -->
      <div class="session-head">
        <span class="session-title">历史会话</span>
        <el-button
          text
          type="primary"
          :icon="Plus"
          size="small"
          @click="handleNewChat"
        >
          新建
        </el-button>
      </div>

      <el-scrollbar class="session-scroll">
        <div v-if="chatStore.loadingSessions" class="session-empty">加载中…</div>
        <div v-else-if="chatStore.sessions.length === 0" class="session-empty">
          该课程暂无历史会话
        </div>
        <ul v-else class="session-list">
          <li
            v-for="session in chatStore.sessions"
            :key="session.id"
            class="session-item"
            :class="{ active: session.id === chatStore.currentSessionId }"
            @click="handleSelectSession(session.id)"
          >
            <div class="session-text">{{ session.title }}</div>
            <div class="session-time">{{ formatTime(session.createdAt) }}</div>
          </li>
        </ul>
      </el-scrollbar>

      <div class="sidebar-footer">
        <span class="user-name">{{ userStore.nickname || '未登录' }}</span>
        <el-button text size="small" @click="handleLogout">退出</el-button>
      </div>
    </aside>

    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
/**
 * 学生端主布局（C1.5）
 * 左侧：课程切换 + 历史会话导航；右侧：主操作区（智能答疑工作台）。
 * 数据来源：Week 1 走 Mock（src/config/index.ts 的 USE_MOCK）。
 * 说明：右侧「课件出处溯源抽屉」属 C2.3，本周不实现。
 */
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';

import { listCourses } from '@/api/course';
import { useChatStore } from '@/stores/chatStore';
import { useCourseStore } from '@/stores/courseStore';
import { useUserStore } from '@/stores/userStore';

const router = useRouter();
const userStore = useUserStore();
const courseStore = useCourseStore();
const chatStore = useChatStore();

/** 会话时间只显示到分钟，后端返回 ISO 字符串（如 2026-09-12T20:31:05） */
function formatTime(value: string): string {
  if (!value) return '';
  return value.replace('T', ' ').slice(5, 16);
}

async function loadCourseAndSessions(): Promise<void> {
  courseStore.setCourses(await listCourses());
  await chatStore.loadSessions(courseStore.currentCourseId);
}

/** 切换课程 → 重新拉该课程的历史会话（换课程不串会话） */
async function handleCourseChange(courseId: number): Promise<void> {
  courseStore.selectCourse(courseId);
  await chatStore.loadSessions(courseId);
}

/** 新建会话：本地清空展示，首次提问时 sessionId 传 0 由后端懒创建（契约 4.2） */
function handleNewChat(): void {
  chatStore.currentSessionId = 0;
  chatStore.messages = [];
  void router.push('/student/chat');
}

async function handleSelectSession(sessionId: number): Promise<void> {
  await chatStore.selectSession(sessionId);
  void router.push('/student/chat');
}

async function handleLogout(): Promise<void> {
  userStore.clearSession();
  chatStore.clear();
  ElMessage.success('已退出登录');
  await router.replace('/login');
}

onMounted(() => {
  void loadCourseAndSessions();
});
</script>

<style scoped>
.student-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ------------------------------ 左侧栏 ------------------------------ */
.sidebar {
  display: flex;
  flex-direction: column;
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid var(--app-border);
  background: #ffffff;
}

.sidebar-brand {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 18px 16px 12px;
}

.brand-title {
  font-size: 18px;
  font-weight: 700;
  color: #1e293b;
}

.brand-sub {
  font-size: 12px;
  color: var(--app-text-muted);
}

.course-switch {
  padding: 0 16px 12px;
}

.course-select {
  width: 100%;
}

.session-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px 6px;
  border-top: 1px solid var(--app-border);
  padding-top: 12px;
}

.session-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-muted);
}

.session-scroll {
  flex: 1;
  min-height: 0;
}

.session-empty {
  padding: 24px 16px;
  font-size: 12px;
  color: var(--app-text-muted);
  text-align: center;
}

.session-list {
  margin: 0;
  padding: 0 8px 8px;
  list-style: none;
}

.session-item {
  padding: 8px 10px;
  margin-bottom: 4px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.session-item:hover {
  background: #f1f5f9;
}

.session-item.active {
  background: var(--el-color-primary-light-9);
}

.session-text {
  font-size: 13px;
  color: var(--app-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item.active .session-text {
  color: var(--el-color-primary);
  font-weight: 600;
}

.session-time {
  margin-top: 2px;
  font-size: 11px;
  color: var(--app-text-muted);
}

.sidebar-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-top: 1px solid var(--app-border);
}

.user-name {
  font-size: 13px;
  color: var(--app-text);
}

/* ------------------------------ 主操作区 ------------------------------ */
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
</style>
