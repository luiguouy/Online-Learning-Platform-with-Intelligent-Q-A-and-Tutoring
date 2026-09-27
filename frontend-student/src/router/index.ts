import { createRouter, createWebHistory } from 'vue-router';
import { ElMessage } from 'element-plus';

import { useUserStore } from '@/stores/userStore';
import type { UserRole } from '@/types';

/**
 * 路由 + 全局守卫（C1.4）
 *
 * 守卫规则来源：AGENT_INSTRUCTIONS.md 2.3
 * - 未登录用户访问除 /login 外的任何路径 → 强制重定向到 /login（本期无注册功能）
 * - 非学生身份访问 /student/** → 提示并拦截（对应"学生越权访问教师后台"的反向规则）
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/auth/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/student',
      component: () => import('@/views/student/StudentLayout.vue'),
      meta: { roles: ['STUDENT'] as UserRole[] },
      children: [
        { path: '', redirect: '/student/chat' },
        {
          path: 'chat',
          name: 'student-chat',
          component: () => import('@/views/student/ChatWorkspace.vue'),
          meta: { title: '智能答疑' },
        },
        // C2.5 知识点解析展示面板、C2.6 历史会话交互在此扩展
      ],
    },
    { path: '/', redirect: '/student/chat' },
    { path: '/:pathMatch(.*)*', redirect: '/student/chat' },
  ],
});

router.beforeEach((to) => {
  const userStore = useUserStore();
  const isPublic = to.meta.public === true;

  if (isPublic) {
    // 已登录学生访问登录页 → 直接进工作台
    if (userStore.isStudent) {
      return { path: '/student/chat' };
    }
    return true;
  }

  if (!userStore.isLoggedIn) {
    // 携带 redirect，登录成功后回到原页面（与 request.ts 的 401 处理保持一致）
    return { path: '/login', query: { redirect: to.fullPath } };
  }

  const roles = to.meta.roles as UserRole[] | undefined;
  if (roles && !roles.includes(userStore.role as UserRole)) {
    ElMessage.error('无权访问学生端工作台，请使用学生账号登录');
    userStore.clearSession();
    return { path: '/login' };
  }

  return true;
});

export default router;
