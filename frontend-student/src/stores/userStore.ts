import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { LoginResult, UserRole } from '@/types';

/**
 * 登录态（学生端）
 *
 * token 的 localStorage 键名固定为 `satoken`（DEV_SPECIFICATION.md 4.2 锁定），
 * 请求头统一 `Authorization: Bearer <token>`。
 * role / nickname 的键名文档未约定：本工程与前端的另一工程 frontend-teacher/ 对齐，
 * 统一使用 `role` / `nickname`（便于两端 Reviewer 互审时口径一致）。
 */
const TOKEN_KEY = 'satoken';
const ROLE_KEY = 'role';
const NICKNAME_KEY = 'nickname';

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '');
  const role = ref<UserRole | ''>((localStorage.getItem(ROLE_KEY) as UserRole | null) ?? '');
  const nickname = ref<string>(localStorage.getItem(NICKNAME_KEY) ?? '');

  const isLoggedIn = computed(() => token.value.length > 0);
  const isStudent = computed(() => role.value === 'STUDENT');

  function setSession(result: LoginResult): void {
    token.value = result.token;
    role.value = result.role;
    nickname.value = result.nickname ?? '';
    localStorage.setItem(TOKEN_KEY, token.value);
    localStorage.setItem(ROLE_KEY, role.value);
    localStorage.setItem(NICKNAME_KEY, nickname.value);
  }

  function clearSession(): void {
    token.value = '';
    role.value = '';
    nickname.value = '';
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ROLE_KEY);
    localStorage.removeItem(NICKNAME_KEY);
  }

  return { token, role, nickname, isLoggedIn, isStudent, setSession, clearSession };
});
