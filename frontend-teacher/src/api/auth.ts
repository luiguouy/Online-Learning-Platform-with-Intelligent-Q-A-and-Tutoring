import request from '@/utils/request';
import type { LoginResult } from '@/types';

/**
 * 登录
 * 接口：POST /api/auth/login（成员 B 提供，见 TEAM_WORK_DIVISION.md 第三章接口矩阵）
 *
 * D2.1（2026-09-17）：解除 Mock。返回体为 `Result<LoginVO>`，经拦截器解包后得 LoginResult
 * （token / userId / username / nickname / role / avatarUrl，Q7 已确认）。
 * ⚠️ 注册只面向学生（`/api/auth/register`），**教师端没有注册入口**，账号由 data.sql 预置；
 * 用户名错与密码错后端返回同一句提示，前端无需区分。
 */
export async function login(username: string, password: string): Promise<LoginResult> {
  return request.post<unknown, LoginResult>('/auth/login', { username, password });
}

/**
 * 退出登录（服务端注销令牌）
 * 接口：POST /api/auth/logout（需已登录，带 Authorization 头）
 *
 * 为何不能只清本地：sa-token 的 timeout 是 7 天，只删 localStorage 的话
 * 令牌在服务端仍有效七天（公用电脑 / 令牌被复制时无从主动吊销）。
 *
 * 调用约定：**失败也要清本地并跳登录页**（幂等）。令牌已过期时接口会回 401，
 * 这不是错误而是“已经不需要注销”，因此调用方不得因接口失败而保留登录态。
 */
export async function logout(): Promise<void> {
  await request.post('/auth/logout');
}
