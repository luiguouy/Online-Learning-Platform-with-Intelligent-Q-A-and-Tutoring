import request from '@/utils/request';
import { USE_MOCK } from '@/config';
import { mockLogin } from '@/mock/auth';
import type { LoginResult } from '@/types';

/**
 * 登录
 * 接口：POST /api/auth/login（成员 B 提供，见 TEAM_WORK_DIVISION.md 第三章接口矩阵）
 * Mock 分支：B1.4 截止 Day 3，Week 1 前端无法真实登录，改走本地模拟以验证 D1.2 角色守卫。
 */
export async function login(username: string, password: string): Promise<LoginResult> {
  if (USE_MOCK) {
    return mockLogin(username, password);
  }
  return request.post<unknown, LoginResult>('/auth/login', { username, password });
}
