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
 * 本期无注册接口（/api/auth/register 不存在），账号由 data.sql 种子数据预置。
 */
export async function login(username: string, password: string): Promise<LoginResult> {
  return request.post<unknown, LoginResult>('/auth/login', { username, password });
}
