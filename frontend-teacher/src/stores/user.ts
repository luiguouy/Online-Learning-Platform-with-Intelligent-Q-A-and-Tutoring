import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { LoginResult, UserRole } from '@/types';

/**
 * 登录态
 * token 的 localStorage 键名固定为 `satoken`（DEV_SPECIFICATION.md 4.2 锁定）；
 * role / nickname 的键名文档未约定，暂用 `role` / `nickname`（已列入待确认项）。
 */
const TOKEN_KEY = 'satoken';
const ROLE_KEY = 'role';
const NICKNAME_KEY = 'nickname';

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '');
  const role = ref<UserRole | ''>((localStorage.getItem(ROLE_KEY) as UserRole | null) ?? '');
  const nickname = ref<string>(localStorage.getItem(NICKNAME_KEY) ?? '');

  const isLoggedIn = computed(() => token.value.length > 0);
  const isTeacher = computed(() => role.value === 'TEACHER');

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

  return { token, role, nickname, isLoggedIn, isTeacher, setSession, clearSession };
});
