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

/**
 * 退出登录（服务端注销令牌）
 * 接口：POST /api/auth/logout（需已登录，带 Authorization 头）
 *
 * 为何不能只清本地：sa-token 的 timeout 是 7 天，只删 localStorage 的话
 * 令牌在服务端仍有效七天（公用电脑 / 令牌被复制时无从主动吊销）。
 *
 * 调用约定：**失败也要清本地并跳登录页**（幂等）。令牌已过期时接口回 401，
 * 那是“已经不需要注销”而不是错误，调用方不得因接口失败而保留登录态。
 *
 * ⚠️ 本函数尚无调用点：学生端的退出入口在 StudentLayout.vue / userStore（成员 C 在改进中），
 * 接线跟进见安全加固 Issue；教师端已接（TeacherLayout.vue 的 handleLogout）。
 */
export async function logout(): Promise<void> {
  await request.post('/auth/logout');
}
