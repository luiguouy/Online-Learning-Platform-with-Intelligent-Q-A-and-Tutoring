import request from '@/utils/request';
import type { LoginResult } from '@/types';

/**
 * 登录
 * 接口：POST /api/auth/login（成员 B 提供，见 TEAM_WORK_DIVISION.md 第三章接口矩阵）
 *
 * D2.1（2026-09-17）：解除 Mock。返回体为 `Result<LoginVO>`，经拦截器解包后得 LoginResult
 * （token / userId / username / nickname / role / avatarUrl，Q7 已确认）。
 * ⚠️ 本期**没有** /api/auth/register；用户名错与密码错后端返回同一句提示，前端无需区分。
 */
export async function login(username: string, password: string): Promise<LoginResult> {
  return request.post<unknown, LoginResult>('/auth/login', { username, password });
}
