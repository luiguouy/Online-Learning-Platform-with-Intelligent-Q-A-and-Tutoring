import request from '@/utils/request';
import type { LoginResult } from '@/types';

/**
 * 登录
 * 接口：POST /api/auth/login（成员 B 提供，见 TEAM_WORK_DIVISION.md 第三章接口矩阵）
 * 返回：Result<LoginVO>，解包后取 token / role / nickname / userId（B 回复确认单 Q7 已核对源码）
 *
 * C2.4：第 1 周的本地模拟数据（src/mock/）与 Mock 开关已按 README 第八节清理清单删除，
 * 本函数只打真实后端。
 * 后端未启动 / 账号密码不对，由 request.ts 的响应拦截器统一弹提示，这里不再兜底。
 */
export async function login(username: string, password: string): Promise<LoginResult> {
  return request.post<unknown, LoginResult>('/auth/login', { username, password });
}

/**
 * 注册（仅学生）
 * 接口：POST /api/auth/register（官方功能，契约见 backend/README-成员B后端代码说明.md §3.1.1）
 * 该接口在 Sa-Token 拦截器里显式放行，前端调用时不需要带 Authorization 头。
 *
 * 后端行为：固定落 STUDENT 角色（不接受 role 入参，防越权注册教师），
 * 注册成功即登录并返回与 /auth/login 相同的 LoginVO，前端免二次登录。
 * 用户名重复返回 400「用户名已存在」，由 request.ts 响应拦截器统一弹提示。
 */
export async function register(
  username: string,
  password: string,
  nickname: string,
): Promise<LoginResult> {
  return request.post<unknown, LoginResult>('/auth/register', { username, password, nickname });
}
