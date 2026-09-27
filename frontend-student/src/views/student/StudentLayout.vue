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
        <!--
          三态（C3.1）：先判「确实失败了」，再判加载中，最后才是空。
          顺序不能反 —— 失败时 loadingSessions 已经复位成 false，若先判空态，
          断网会被显示成「该课程暂无历史会话」，把故障说成"没有数据"。
        -->
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
            <div class="session-text">{{ session.sessionTitle }}</div>
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
 * 学生端主布局（C1.5 布局 / C2.4 转真实接口 / C2.6 会话切换 / C3.1 三态打磨）
 * 左侧：课程切换 + 历史会话导航；右侧：主操作区（智能答疑工作台）。
 * 数据来源：真实后端 —— 课程 GET /api/course/list，会话与明细 GET /api/qa/sessions、/api/qa/records。
 * （Week 1 的本地模拟数据 src/mock/ 与 Mock 开关已在 C2.4 按 README 第八节清理清单删除。）
 * 右侧「课件出处溯源抽屉」（C2.3）、「知识点精解」（C2.5）、点赞点踩（C2.6）都在工作台侧实现。
 * C3.1：侧栏加载中 → 骨架屏；加载失败 → 友好文案 + 重试；空 → 空态组件。
 */
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';

import StatePlaceholder from '@/components/StatePlaceholder.vue';
import { listCourses } from '@/api/course';
import { useChatStore } from '@/stores/chatStore';
import { useCourseStore } from '@/stores/courseStore';
import { useUserStore } from '@/stores/userStore';

const router = useRouter();
const userStore = useUserStore();
const courseStore = useCourseStore();
const chatStore = useChatStore();

/**
 * 课程下拉框自身的加载态。
 * 不复用 `courses.length === 0`：课程列表为空既可能是「还在加载」，也可能是
 * 「加载失败/确实没有课程」，两者靠数组长度无法区分，失败时下拉框会永久转圈。
 */
const loadingCourses = ref(true);

/**
 * 侧栏加载失败原因（C3.1）。
 * 课程列表与会话列表共用一条：两者是同一条链路上的连续动作（会话要按 courseId 拉），
 * 分开渲染会出现「课程失败 + 会话空态」两条互相矛盾的提示，反而让人以为没数据。
 */
const loadError = ref('');

/** 侧栏空态文案：一门课都没有时不该说「该课程暂无历史会话」——根本没有"该课程" */
const emptySessionsText = computed(() =>
  courseStore.currentCourseId ? '该课程暂无历史会话' : '请先选择课程',
);

/** 会话时间只显示到分钟，后端返回 ISO 字符串（如 2026-09-12T20:31:05） */
function formatTime(value: string): string {
  if (!value) return '';
  return value.replace('T', ' ').slice(5, 16);
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
