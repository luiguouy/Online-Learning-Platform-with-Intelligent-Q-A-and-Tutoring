/**
 * 时间格式化工具（后端时间字段统一入口）。
 *
 * 后端所有时间字段（`createdAt` / `updatedAt` 等）均为 ISO 格式且**不带时区后缀**，
 * 例如 `2026-09-17T09:44:47`。直接渲染会露出中间的 `T`，观感差且各页表现不一，
 * 因此统一走本模块 —— 新增页面请复用，不要再各写一份。
 */

/**
 * 把后端 ISO 时间串转成易读的「YYYY-MM-DD HH:mm:ss」。
 *
 * @param value 后端时间字段（如 `2026-09-17T09:44:47`）；空值返回占位符 `—`
 * @returns 形如 `2026-09-17 09:44:47` 的字符串
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  return value.replace('T', ' ').slice(0, 19);
}
