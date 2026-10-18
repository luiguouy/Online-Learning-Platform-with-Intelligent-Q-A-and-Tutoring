import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { getUserProfile } from '@/api/user';
import type { LoginResult, UserProfile, UserRole } from '@/types';

/**
 * 登录态与用户个人资料 Store（学生端）
 */
const TOKEN_KEY = 'satoken';
const ROLE_KEY = 'role';
const NICKNAME_KEY = 'nickname';
const AVATAR_KEY = 'avatar_url';
const BIO_KEY = 'bio';

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '');
  const role = ref<UserRole | ''>((localStorage.getItem(ROLE_KEY) as UserRole | null) ?? '');
  const nickname = ref<string>(localStorage.getItem(NICKNAME_KEY) ?? '');
  const avatarUrl = ref<string>(localStorage.getItem(AVATAR_KEY) ?? '');
  const bio = ref<string>(localStorage.getItem(BIO_KEY) ?? '');

  const isLoggedIn = computed(() => token.value.length > 0);
  const isStudent = computed(() => role.value === 'STUDENT');

  function setSession(result: LoginResult): void {
    token.value = result.token;
    role.value = result.role;
    nickname.value = result.nickname ?? '';
    avatarUrl.value = result.avatarUrl ?? '';
    bio.value = result.bio ?? '';
    localStorage.setItem(TOKEN_KEY, token.value);
    localStorage.setItem(ROLE_KEY, role.value);
    localStorage.setItem(NICKNAME_KEY, nickname.value);
    localStorage.setItem(AVATAR_KEY, avatarUrl.value);
    localStorage.setItem(BIO_KEY, bio.value);
  }

  function setProfile(profile: UserProfile): void {
    nickname.value = profile.nickname ?? '';
    avatarUrl.value = profile.avatarUrl ?? '';
    bio.value = profile.bio ?? '';
    localStorage.setItem(NICKNAME_KEY, nickname.value);
    localStorage.setItem(AVATAR_KEY, avatarUrl.value);
    localStorage.setItem(BIO_KEY, bio.value);
  }

  async function fetchProfile(): Promise<UserProfile | null> {
    if (!isLoggedIn.value) return null;
    try {
      const profile = await getUserProfile();
      setProfile(profile);
      return profile;
    } catch {
      return null;
    }
  }

  function clearSession(): void {
    token.value = '';
    role.value = '';
    nickname.value = '';
    avatarUrl.value = '';
    bio.value = '';
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ROLE_KEY);
    localStorage.removeItem(NICKNAME_KEY);
    localStorage.removeItem(AVATAR_KEY);
    localStorage.removeItem(BIO_KEY);
  }

  return {
    token,
    role,
    nickname,
    avatarUrl,
    bio,
    isLoggedIn,
    isStudent,
    setSession,
    setProfile,
    fetchProfile,
    clearSession,
  };
});
