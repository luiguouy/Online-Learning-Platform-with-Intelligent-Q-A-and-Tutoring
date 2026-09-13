/**
 * Mock 数据：登录（Week 1 前端自测用）
 *
 * 背景：成员 B 的登录接口 B1.4 截止 Day 3，Week 1 前端无法真实登录，
 * 因此本地模拟登录结果，用于验证 D1.2「路由 + 角色守卫」——学生账号必须被拒。
 * D2.1 之后删除本文件（见 src/config/index.ts 的 USE_MOCK 说明）。
 */
import type { LoginResult, UserRole } from '@/types';

/** 与 AGENT_INSTRUCTIONS.md 3.1 种子数据保持一致的账号 */
const SEED_USERS: Record<string, { password: string; role: UserRole; nickname: string; userId: number }> = {
  teacher01: { password: '123456', role: 'TEACHER', nickname: '王教授', userId: 1 },
  student01: { password: '123456', role: 'STUDENT', nickname: '李明', userId: 2 },
  student02: { password: '123456', role: 'STUDENT', nickname: '张华', userId: 3 },
};

export function mockLogin(username: string, password: string): LoginResult {
  const user = SEED_USERS[username.trim()];
  if (!user) {
    throw new Error('账号不存在（Mock 模式仅支持 teacher01 / student01 / student02）');
  }
  if (user.password !== password) {
    throw new Error('密码错误');
  }
  return {
    token: `mock-token-${username}-${Date.now()}`,
    role: user.role,
    nickname: user.nickname,
    userId: user.userId,
  };
}
