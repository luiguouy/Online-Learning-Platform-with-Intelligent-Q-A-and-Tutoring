<template>
  <el-container class="layout">
    <el-aside width="220px" class="layout-aside">
      <div class="brand">
        <span class="brand-title">智能答疑平台</span>
        <span class="brand-sub">教师管理后台</span>
      </div>

      <el-menu :default-active="activeMenu" router class="layout-menu">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="layout-header">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-for="(crumb, index) in breadcrumbs" :key="index">
            {{ crumb }}
          </el-breadcrumb-item>
        </el-breadcrumb>

        <div class="header-right">
          <el-select
            v-model="currentCourseId"
            class="course-select"
            placeholder="选择课程"
            :loading="courseLoading"
          >
            <el-option
              v-for="course in courses"
              :key="course.id"
              :label="course.courseName"
              :value="course.id"
            />
          </el-select>

          <span class="user-name">{{ userStore.nickname || '教师' }}</span>
          <el-button link type="primary" @click="handleLogout">退出登录</el-button>
        </div>
      </el-header>

      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
/**
 * 教师端通用布局（D1.2）
 * 结构：左侧菜单 + 顶部面包屑 + 主工作台（MEMBER_D_DEV_GUIDE.md 一）
 * 本期只挂「课件知识库管理」一个菜单；「问答记录查看」在 D2.3 加入。
 */
import { computed, onMounted } from 'vue';
import { storeToRefs } from 'pinia';
import { useRoute, useRouter } from 'vue-router';
import { Document } from '@element-plus/icons-vue';
import type { Component } from 'vue';

import { useCourseStore } from '@/stores/course';
import { useUserStore } from '@/stores/user';

interface MenuItem {
  path: string;
  label: string;
  icon: Component;
}

const menus: MenuItem[] = [
  { path: '/teacher/docs', label: '课件知识库管理', icon: Document },
];

const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const courseStore = useCourseStore();

const { courses, currentCourseId, loading: courseLoading } = storeToRefs(courseStore);

const activeMenu = computed(() => route.path);

const breadcrumbs = computed<string[]>(() => {
  const title = route.meta.title;
  return ['教师管理后台', typeof title === 'string' ? title : '工作台'];
});

function handleLogout(): void {
  userStore.clearSession();
  router.replace('/login');
}

onMounted(() => {
  courseStore.loadCourses();
});
</script>

<style scoped>
.layout {
  height: 100vh;
}

.layout-aside {
  display: flex;
  flex-direction: column;
  background-color: #ffffff;
  border-right: 1px solid var(--app-border);
}

.brand {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 20px 20px 18px;
  border-bottom: 1px solid var(--app-border);
}

.brand-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--app-text);
}

.brand-sub {
  font-size: 12px;
  color: var(--app-text-muted);
}

.layout-menu {
  flex: 1;
  border-right: none;
}

.layout-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  background-color: #ffffff;
  border-bottom: 1px solid var(--app-border);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.course-select {
  width: 240px;
}

.user-name {
  font-size: 13px;
  color: var(--app-text-muted);
}

.layout-main {
  padding: 20px;
  background-color: var(--app-bg);
}
</style>
