/**
 * 请求层统一封装（C1.2）
 * 模板来源：AGENT_INSTRUCTIONS.md 2.1（拷贝自该节，未做结构改动）
 *
 * 铁律（DEV_SPECIFICATION.md 4.2，v6.0 锁定）：
 * - token 存 localStorage，键名固定 `satoken`（只是本地存储名，与请求头名无关）
 * - 请求头只发一个：Authorization: Bearer <token>，头值必须带 "Bearer " 前缀（含一个空格）
 *   只发裸 token 会被判未登录返回 401
 * - 页面里严禁使用裸 axios，必须走本实例
 *
 * 与模板的唯一差异（与 frontend-teacher/ 保持一致，PR #27 评审结论）：
 * 401 分支改为复用 user store 的 clearSession()，保证 satoken / role / nickname
 * 三键对称清理；跳转登录页携带 redirect 参数，与路由守卫行为保持一致。
 */
import axios from 'axios';
import type { AxiosError } from 'axios';
import { ElMessage } from 'element-plus';

import { useUserStore } from '@/stores/userStore';

const request = axios.create({
  baseURL: '/api',
  timeout: 20000,
});

/** 后端统一包装体（DEV_SPECIFICATION.md 4.1：{ code, message, data }） */
interface ResultBody {
  code?: number;
  message?: string;
}

/**
 * 把传输层错误翻成一句用户能看懂的话（C3.1）。
 *
 * 为什么必须在这一层做：视图的「失败态」占位直接展示 `error.message`，
 * 而 AxiosError 的默认 message 对用户没有意义（"Network Error" / "timeout of 20000ms exceeded"）。
 * 归一到此处之后，各视图不必再各自判断错误类型，文案也不会各写一版。
 *
 * 与拦截器弹窗的分工：ElMessage 是一闪而过的「告知」；失败态占位是常驻的「可重试」出口。
 * 两者用同一句话，不是重复提示。
 */
function toFriendlyMessage(error: AxiosError<ResultBody>): string {
  // 主动取消（组件卸载、切会话中断上一次请求）不是故障
  if (axios.isCancel(error)) return '请求已取消';

  // 超时：axios 在 timeout 触发时把 code 置为 ECONNABORTED
  if (error.code === 'ECONNABORTED' || /timeout/i.test(error.message)) {
    return '请求超时，请检查网络后重试';
  }

  // 拿不到 response = 断网 / 后端未启动 / 被跨域拦下
  if (!error.response) {
    return '无法连接服务器，请检查网络后重试';
  }

  const status = error.response.status;
  const fromServer = error.response.data?.message;
  if (status >= 500) {
    return fromServer || `服务暂时不可用（HTTP ${status}），请稍后重试`;
  }
  return fromServer || `请求失败（HTTP ${status}），请稍后重试`;
}

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
      // 复用 user store 的 clearSession()，保证 satoken / role / nickname 三键对称清理
      useUserStore().clearSession();
      // 与路由守卫对齐：携带 redirect，登录成功后回到原页面
      const current = encodeURIComponent(window.location.pathname + window.location.search);
      window.location.href = `/login?redirect=${current}`;
      return Promise.reject(new Error(res.message || '登录已过期'));
    }
    ElMessage.error(res.message || '请求处理失败');
    return Promise.reject(new Error(res.message || '请求处理失败'));
  },
  /**
   * 传输层失败（断网 / 超时 / 4xx / 5xx）→ 归一文案 + 弹窗 + 抛可读 Error。
   *
   * 这里把 axios 的原始错误换成 `new Error(可读文案)`：C3.1 的失败态占位要拿
   * `error.message` 直接上屏，保留 AxiosError 只会让视图拿到 "Network Error" 这种英文串。
   * 排查信息不丢 —— 完整错误仍由 axios 自己打到控制台。
   */
  (error: AxiosError<ResultBody>) => {
    const message = toFriendlyMessage(error);
    // 用户主动取消不是故障，弹提示反而像出错
    if (!axios.isCancel(error)) {
      ElMessage.error(message);
    }
    return Promise.reject(new Error(message));
  },
);

export default request;
