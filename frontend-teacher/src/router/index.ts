import { createRouter, createWebHistory } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useUserStore } from '@/stores/user';
import type { UserRole } from '@/types';

/**
 * 路由 + 角色守卫（D1.2）
 *
 * 守卫规则来源：AGENT_INSTRUCTIONS.md 2.3
 * - 未登录访问除 /login 外的路径 → 强制跳 /login（本期无注册功能）
 * - 学生身份访问教师后台 → 提示「无权访问教师管理后台」并拦截
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
      path: '/teacher',
      component: () => import('@/views/teacher/TeacherLayout.vue'),
      meta: { roles: ['TEACHER'] as UserRole[] },
      children: [
        { path: '', redirect: '/teacher/docs' },
        {
          path: 'docs',
          name: 'teacher-docs',
          component: () => import('@/views/teacher/CourseDocManage.vue'),
          meta: { title: '课件知识库管理' },
        },
        // D2.3 在此新增「问答记录查看」子路由 /teacher/qa-records
      ],
    },
    { path: '/', redirect: '/teacher/docs' },
    { path: '/:pathMatch(.*)*', redirect: '/teacher/docs' },
  ],
});

router.beforeEach((to) => {
  const userStore = useUserStore();
  const isPublic = to.meta.public === true;

  if (isPublic) {
    // 已登录教师访问登录页 → 直接进后台；学生留在登录页以便重新登录
    if (userStore.isTeacher) {
      return { path: '/teacher/docs' };
    }
    return true;
  }

  if (!userStore.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }

  const roles = to.meta.roles as UserRole[] | undefined;
  if (roles && !roles.includes(userStore.role as UserRole)) {
    // 文档原始要求是跳转学生端工作台 /student/chat，但该路由属于成员 C 的学生端工程，
    // 本工程不存在，故改为清除会话后回登录页（已记入 README 待确认项）
    ElMessage.error('无权访问教师管理后台');
    userStore.clearSession();
    return { path: '/login' };
  }

  return true;
});

export default router;
