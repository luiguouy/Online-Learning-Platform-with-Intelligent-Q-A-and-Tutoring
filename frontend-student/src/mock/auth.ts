/**
 * Mock 数据：登录（Week 1 前端自测用）
 *
 * 背景：成员 B 的登录接口 B1.4 截止 Day 3，Week 1 前端无法真实登录，
 * 因此本地模拟登录结果，用于自测 C1.4「登录页 + 路由守卫」——教师账号必须被拒。
 * 对接真实接口后删除本文件（见 src/config/index.ts 的 USE_MOCK 说明）。
 */
import type { LoginResult, UserRole } from '@/types';

/** 与 AGENT_INSTRUCTIONS.md 3.1 种子数据保持一致的账号 */
const SEED_USERS: Record<
  string,
  { password: string; role: UserRole; nickname: string; userId: number }
> = {
  teacher01: { password: '123456', role: 'TEACHER', nickname: '王教授', userId: 1 },
  student01: { password: '123456', role: 'STUDENT', nickname: '李明', userId: 2 },
  student02: { password: '123456', role: 'STUDENT', nickname: '张华', userId: 3 },
};

export function mockLogin(username: string, password: string): LoginResult {
  const user = SEED_USERS[username.trim()];
  // 后端对「用户名错」与「密码错」返回同一句提示（防账号枚举，B 回复确认单 Q7），
  // Mock 侧同样不区分，保持一致行为。
  if (!user || user.password !== password) {
    throw new Error('用户名或密码错误');
  }
  return {
    token: `mock-token-${username}-${Date.now()}`,
    role: user.role,
    nickname: user.nickname,
    userId: user.userId,
    username,
    avatarUrl: '',
  };
}
