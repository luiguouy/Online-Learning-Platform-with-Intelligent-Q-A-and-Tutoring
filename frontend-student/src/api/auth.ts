import request from '@/utils/request';
import { USE_MOCK } from '@/config';
import { mockLogin } from '@/mock/auth';
import type { LoginResult } from '@/types';

/**
 * 登录
 * 接口：POST /api/auth/login（成员 B 提供，见 TEAM_WORK_DIVISION.md 第三章接口矩阵）
 * 返回：Result<LoginVO>，解包后取 token / role / nickname / userId（B 回复确认单 Q7 已核对源码）
 * Mock 分支：B1.4 截止 Day 3，Week 1 前端先本地模拟，用于自测 C1.4 登录跳转与路由守卫。
 * 本期无注册接口（/api/auth/register 不存在），账号由种子数据预置。
 */
export async function login(username: string, password: string): Promise<LoginResult> {
  if (USE_MOCK) {
    return mockLogin(username, password);
  }
  return request.post<unknown, LoginResult>('/auth/login', { username, password });
}
