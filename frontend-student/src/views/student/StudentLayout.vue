<template>
  <div class="student-layout">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <div class="brand-logo">
          <el-icon :size="20"><ChatDotRound /></el-icon>
        </div>
        <div class="brand-text">
          <span class="brand-title">智能学伴</span>
          <span class="brand-subtitle">大学课程辅导平台</span>
        </div>
      </div>

      <!-- 课程切换：卡片化选择 -->
      <div class="course-switch">
        <div class="switch-label">当前学习课程</div>
        <el-select
          v-model="courseStore.currentCourseId"
          :loading="loadingCourses"
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

      <!-- 历史会话栏：增加活跃 / 归档箱 Tab 切换 -->
      <div class="session-section-header">
        <div class="tab-group">
          <button
            class="tab-btn"
            :class="{ active: chatStore.sessionTab === 'active' }"
            @click="handleSwitchTab('active')"
          >
            活跃会话
          </button>
          <button
            class="tab-btn"
            :class="{ active: chatStore.sessionTab === 'archived' }"
            @click="handleSwitchTab('archived')"
          >
            归档箱
          </button>
        </div>
        <el-button
          type="primary"
          :icon="Plus"
          size="small"
          class="new-chat-btn"
          @click="handleNewChat"
        >
          新建
        </el-button>
      </div>

      <el-scrollbar class="session-scroll">
        <StatePlaceholder
          v-if="loadError"
          state="error"
          error-title="加载失败"
          :description="loadError"
          @retry="handleRetryLoad"
        />
        <StatePlaceholder v-else-if="chatStore.loadingSessions" state="loading" :rows="4" />
        <StatePlaceholder
          v-else-if="chatStore.sessions.length === 0"
          state="empty"
          :description="emptySessionsText"
          :image-size="60"
        />
        <ul v-else class="session-list">
          <li
            v-for="session in chatStore.sessions"
            :key="session.id"
            class="session-item"
            :class="{ active: session.id === chatStore.currentSessionId }"
            @click="handleSelectSession(session.id)"
          >
            <el-icon class="session-icon"><ChatDotRound /></el-icon>
            <div class="session-info">
              <div class="session-text" :title="session.sessionTitle">{{ session.sessionTitle }}</div>
              <div class="session-time">{{ formatTime(session.createdAt) }}</div>
            </div>
            <!-- 悬浮操作栏：归档与删除 -->
            <div class="session-actions" @click.stop>
              <el-tooltip :content="chatStore.sessionTab === 'archived' ? '取消归档' : '归档会话'" placement="top">
                <button class="action-btn" @click="handleArchiveSession(session.id)">
                  <el-icon :size="13"><Box /></el-icon>
                </button>
              </el-tooltip>
              <el-popconfirm
                title="确定删除此会话及其问答记录？"
                confirm-button-text="删除"
                cancel-button-text="取消"
                confirm-button-type="danger"
                @confirm="handleDeleteSession(session.id)"
              >
                <template #reference>
                  <button class="action-btn delete-btn">
                    <el-icon :size="13"><Delete /></el-icon>
                  </button>
                </template>
              </el-popconfirm>
            </div>
          </li>
        </ul>
      </el-scrollbar>

      <div class="sidebar-footer">
        <el-tooltip content="点击进入个人主页" placement="top">
          <div
            class="user-profile"
            :class="{ 'is-active': isProfileActive }"
            @click="goToProfile"
          >
            <div class="user-avatar">
              <img
                v-if="userStore.avatarUrl && !avatarError"
                :src="userStore.avatarUrl"
                alt="头像"
                class="user-avatar-img"
                @error="avatarError = true"
              />
              <span v-else>{{ userInitial }}</span>
            </div>
            <div class="user-meta">
              <span class="user-name">{{ userStore.nickname || '未登录' }}</span>
              <span class="user-role">{{ userStore.bio || '学生个人主页' }}</span>
            </div>
          </div>
        </el-tooltip>
        <el-tooltip content="退出登录" placement="top">
          <button class="logout-icon-btn" @click="handleLogout">
            <el-icon :size="16"><SwitchButton /></el-icon>
          </button>
        </el-tooltip>
      </div>
    </aside>

    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
/**
 * 学生端主布局（C1.5 布局 / C2.4 转真实接口 / C2.6 会话切换 / C3.1 三态打磨）
 * 左侧：课程切换 + 历史会话导航；右侧：主操作区（智能答疑工作台）。
 * 数据来源：真实后端 —— 课程 GET /api/course/list，会话与明细 GET /api/qa/sessions、/api/qa/records。
 * （Week 1 的本地模拟数据 src/mock/ 与 Mock 开关已在 C2.4 按 README 第八节清理清单删除。）
 * 右侧「课件出处溯源抽屉」（C2.3）、「知识点精解」（C2.5）、点赞点踩（C2.6）都在工作台侧实现。
 * C3.1：侧栏加载中 → 骨架屏；加载失败 → 友好文案 + 重试；空 → 空态组件。
 */
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Plus, ChatDotRound, Delete, Box, SwitchButton } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';

import StatePlaceholder from '@/components/StatePlaceholder.vue';
import { listCourses } from '@/api/course';
import { useChatStore } from '@/stores/chatStore';
import { useCourseStore } from '@/stores/courseStore';
import { useUserStore } from '@/stores/userStore';

const router = useRouter();
const route = useRoute();
const userStore = useUserStore();
const courseStore = useCourseStore();
const chatStore = useChatStore();

const isProfileActive = computed(() => route.path === '/student/profile');
const avatarError = ref(false);

watch(
  () => userStore.avatarUrl,
  () => {
    avatarError.value = false;
  },
);

function goToProfile(): void {
  router.push('/student/profile');
}

const loadingCourses = ref(true);
const loadError = ref('');

const userInitial = computed(() => {
  const name = userStore.nickname || '同';
  return name.slice(0, 1).toUpperCase();
});

const emptySessionsText = computed(() => {
  if (!courseStore.currentCourseId) return '请先选择课程';
  return chatStore.sessionTab === 'archived' ? '归档箱暂无会话' : '该课程暂无历史会话';
});

function formatTime(value: string): string {
  if (!value) return '';
  return value.replace('T', ' ').slice(5, 16);
}

/** 切换活跃 / 归档 Tab */
async function handleSwitchTab(tab: 'active' | 'archived'): Promise<void> {
  if (chatStore.sessionTab === tab) return;
  chatStore.sessionTab = tab;
  if (courseStore.currentCourseId) {
    await chatStore.loadSessions(courseStore.currentCourseId);
  }
}

/** 删除会话 */
async function handleDeleteSession(sessionId: number): Promise<void> {
  try {
    await chatStore.removeSession(sessionId, courseStore.currentCourseId);
    ElMessage.success('会话已删除');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败');
  }
}

/** 归档 / 取消归档会话 */
async function handleArchiveSession(sessionId: number): Promise<void> {
  try {
    await chatStore.archiveSession(sessionId, courseStore.currentCourseId);
    ElMessage.success(chatStore.sessionTab === 'archived' ? '已恢复至活跃会话' : '已移入归档箱');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '归档操作失败');
  }
}

/**
 * 拉课程列表 + 首个课程的历史会话。
 *
 * 失败兜底（PR #35 评审意见）：`onMounted` 里的 `void loadCourseAndSessions()` 会吞掉
 * rejection，两个 await 任一失败都成为 unhandled rejection —— C2.4 转真实接口后
 * 后端未启动 / 返回非 200 即会触发（这是 Mock 阶段掩盖不了的真实场景）。
 *
 * C3.1 起不再只是「吞掉」：失败原因落到 `loadError`（模板渲染错误态 + 重试）并同步到
 * courseStore，供工作台区分「没有课程」与「拉课程失败」。
 * 文案由 request.ts 统一归一（断网 / 超时 / 5xx 各有说法），这里直接用 `error.message`。
 * 注：`chatStore.loadSessions` 内部已有 finally 复位 `loadingSessions`，catch 里不重复处理。
 */
async function loadCourseAndSessions(): Promise<void> {
  // 重试会再走一遍这里，所以 loading 与失败态都在此处复位，而不是只依赖初始值
  loadingCourses.value = true;
  loadError.value = '';
  courseStore.setCoursesError('');
  try {
    courseStore.setCourses(await listCourses());
    // 课程列表为空时不必再拉会话：courseId=0 只会换来一次无意义的请求
    // （后端对 0 可能直接报错，那就会把「没有课程」误报成「加载失败」）。
    if (courseStore.currentCourseId) {
      await chatStore.loadSessions(courseStore.currentCourseId);
    } else {
      chatStore.clear();
    }
  } catch (error) {
    // 课程拉不到就没得选，会话也一起清掉，避免残留上一门课的历史
    courseStore.setCourses([]);
    chatStore.clear();
    const message = error instanceof Error ? error.message : '加载失败，请检查网络后重试';
    loadError.value = message;
    courseStore.setCoursesError(message);
  } finally {
    loadingCourses.value = false;
  }
}

/** 侧栏失败态的重试入口（C3.1）：重拉课程列表 + 当前课程的会话 */
function handleRetryLoad(): void {
  void loadCourseAndSessions();
}

/** 切换课程 → 重新拉该课程的历史会话（换课程不串会话） */
async function handleCourseChange(courseId: number): Promise<void> {
  courseStore.selectCourse(courseId);
  loadError.value = '';
  try {
    await chatStore.loadSessions(courseId);
  } catch (error) {
    // 拉取失败必须清掉上一课程的会话，否则侧栏仍显示旧课程列表，
    // 用户点击会用旧 sessionId 拉到另一课程的问答（串会话）。
    chatStore.clear();
    // 拦截器已弹过一次提示（一闪而过）；这里的错误态是常驻且带重试的出口，两者分工不同。
    loadError.value = error instanceof Error ? error.message : '会话加载失败，请重试';
  }
}

/** 新建会话：统一走 store 的 startNewSession（内含断流+作废在途响应），不直接改 state */
function handleNewChat(): void {
  chatStore.startNewSession();
  void router.push('/student/chat');
}

async function handleSelectSession(sessionId: number): Promise<void> {
  try {
    await chatStore.selectSession(sessionId);
  } catch {
    // 兜底：selectSession 内部已把失败转成 recordsError（消息区显示错误态 + 重试），
    // 正常不会再抛；这里只保证模板上的事件处理器不产生 unhandled rejection。
  }
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
  void userStore.fetchProfile();
});
</script>

<style scoped>
.student-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background-color: #f8fafc;
}

/* ------------------------------ 左侧栏 ------------------------------ */
.sidebar {
  display: flex;
  flex-direction: column;
  width: 270px;
  flex-shrink: 0;
  border-right: 1px solid var(--app-border);
  background: var(--app-surface);
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 18px 16px;
  border-bottom: 1px solid var(--app-border-subtle);
}

.brand-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: #2563eb;
  color: #ffffff;
}

.brand-text {
  display: flex;
  flex-direction: column;
}

.brand-title {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.2px;
}

.brand-subtitle {
  font-size: 11px;
  font-weight: 500;
  color: #64748b;
  margin-top: 1px;
}

.course-switch {
  padding: 12px 14px;
  background: #f8fafc;
  margin: 12px 14px 4px;
  border-radius: 8px;
  border: 1px solid var(--app-border);
}

.switch-label {
  font-size: 11px;
  font-weight: 600;
  color: #475569;
  margin-bottom: 6px;
}

.course-select {
  width: 100%;
}

/* Tab 分组与新建按钮 */
.session-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px 8px;
}

.tab-group {
  display: flex;
  background: #f1f5f9;
  padding: 2px;
  border-radius: 6px;
}

.tab-btn {
  border: none;
  background: transparent;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 500;
  color: #64748b;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.tab-btn.active {
  background: #ffffff;
  color: #2563eb;
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.new-chat-btn {
  border-radius: 6px;
  font-weight: 500;
  background: #2563eb;
  border: none;
}

.new-chat-btn:hover {
  background: #1d4ed8;
}

.session-scroll {
  flex: 1;
  min-height: 0;
}

.session-list {
  margin: 0;
  padding: 4px 10px 10px;
  list-style: none;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  margin-bottom: 3px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
  position: relative;
}

.session-item:hover {
  background: #f1f5f9;
}

.session-item.active {
  background: #eff6ff;
  border-left: 3px solid #2563eb;
}

.session-icon {
  color: #94a3b8;
  flex-shrink: 0;
  font-size: 15px;
}

.session-item.active .session-icon {
  color: #2563eb;
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-text {
  font-size: 13px;
  font-weight: 500;
  color: #334155;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item.active .session-text {
  color: #1d4ed8;
  font-weight: 600;
}

.session-time {
  margin-top: 2px;
  font-size: 11px;
  color: #94a3b8;
}

/* 悬浮操作图标条 */
.session-actions {
  display: none;
  align-items: center;
  gap: 4px;
}

.session-item:hover .session-actions {
  display: flex;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 4px;
  background: #e2e8f0;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s ease;
}

.action-btn:hover {
  background: #cbd5e1;
  color: #1e293b;
}

.action-btn.delete-btn:hover {
  background: #fee2e2;
  color: #dc2626;
}

/* 底部用户卡片 */
.sidebar-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-top: 1px solid var(--app-border-subtle);
  background: #fafafa;
}

.user-profile {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 6px 8px;
  border-radius: 8px;
  transition: all 0.15s ease;
  flex: 1;
  min-width: 0;
}

.user-profile:hover {
  background: #f1f5f9;
}

.user-profile.is-active {
  background: #eff6ff;
}

.user-profile.is-active .user-name {
  color: #2563eb;
}

.user-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #2563eb;
  color: #ffffff;
  font-weight: 600;
  font-size: 13px;
  overflow: hidden;
  flex-shrink: 0;
}

.user-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-role {
  font-size: 11px;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 120px;
}

.logout-icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s ease;
}

.logout-icon-btn:hover {
  background: #fee2e2;
  color: #dc2626;
}

/* ------------------------------ 主操作区 ------------------------------ */
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #f8fafc;
}
</style>
