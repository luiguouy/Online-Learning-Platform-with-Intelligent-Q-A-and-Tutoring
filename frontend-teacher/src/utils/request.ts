/**
 * 请求层统一封装
 * 模板来源：AGENT_INSTRUCTIONS.md 2.1（拷贝自该节，未做结构改动）
 *
 * 铁律（DEV_SPECIFICATION.md 4.2，v6.0 锁定）：
 * - token 存 localStorage，键名固定 `satoken`（只是本地存储名，与请求头名无关）
 * - 请求头只发一个：Authorization: Bearer <token>，头值必须带 "Bearer " 前缀（含一个空格）
 * - 页面里严禁使用裸 axios，必须走本实例
 *
 * 偏离记录（2026-09-13，PR #27 评审）：401 分支由「手动清两个键」改为复用
 * user store 的 clearSession()（三键对称清理），跳转登录页携带 redirect 参数，
 * 与路由守卫行为保持一致。
 */
import axios from 'axios';
import { ElMessage } from 'element-plus';

import { useUserStore } from '@/stores/user';

const request = axios.create({
  baseURL: '/api',
  timeout: 20000,
});

// 请求拦截器：统一注入鉴权头（只发这一个头即可）
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('satoken');
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`;
  }
  return config;
});

// 响应拦截器：解包 Result<T>，401 统一跳登录
request.interceptors.response.use(
  (response) => {
    const res = response.data;
    if (res.code === 200) {
      return res.data;
    }
    if (res.code === 401) {
      // 复用 user store 的 clearSession()，保证 satoken / role / nickname 三键对称清理（PR #27 评审）
      useUserStore().clearSession();
      // 与路由守卫对齐：携带 redirect，登录成功后回到原页面
      const current = encodeURIComponent(window.location.pathname + window.location.search);
      window.location.href = `/login?redirect=${current}`;
      return Promise.reject(new Error(res.message || '登录已过期'));
    }
    ElMessage.error(res.message || '请求处理失败');
    return Promise.reject(new Error(res.message || 'Error'));
  },
  (error) => {
    ElMessage.error(error.response?.data?.message || '网络通讯异常');
    return Promise.reject(error);
  },
);

export default request;
