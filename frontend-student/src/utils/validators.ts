/**
 * 表单校验规则的单一来源（学生端）。
 *
 * <p><b>为什么单独成文件</b>：昵称规则同时服务「注册」与「改资料」两个表单，
 * 复制两份就会各自漂移；口令规则更是要与后端约束逐字对齐。规则本身的事实来源是后端：
 * `backend/src/main/java/com/smartqa/platform/validation/StrongPassword.java`
 * 与 `ValidNickname.java`。
 *
 * <b>改阈值必须三处同改</b>：后端约束 → 本文件 → `backend/README` §3.1.1 / §3.7。
 * 提示文案两边逐字一致——本项目吃过「行为与提示不自洽」的亏（文案说 1600 字、
 * 实际 5300 字节就拦截，用户完全无法自助排查）。
 */

/** 与后端 `StrongPassword.MESSAGE` 逐字一致 */
export const PASSWORD_HINT = '密码须为 8-32 位，同时包含字母与数字，且不能使用常见弱口令或纯连续/重复字符';

/** 与后端 `ValidNickname.MESSAGE` 逐字一致 */
export const NICKNAME_HINT = '名称须为 1-30 个字符，且不含控制字符、零宽字符或尖括号';

type ValidateCallback = (error?: Error) => void;

/**
 * 整串等步长 ±1（如 `12345678`、`abcdefgh`），与后端 `isSequential` 同逻辑。
 *
 * 只看整串同一差值，不做子串检测：子串检测会把 `A1b2C3d4` 这类正常口令一并拒掉，
 * 误伤代价大于收益。
 */
export function isSequentialString(value: string): boolean {
  if (value.length < 2) return false;
  const step = value.charCodeAt(1) - value.charCodeAt(0);
  if (step === 0 || Math.abs(step) !== 1) return false;
  for (let i = 2; i < value.length; i++) {
    if (value.charCodeAt(i) - value.charCodeAt(i - 1) !== step) return false;
  }
  return true;
}

/**
 * 口令规则。弱口令黑名单需要字典，交由服务端判定；本函数覆盖可静态判定的部分，
 * 提示文案已包含「常见弱口令」，因此前端放行、后端拒绝时用户看到的仍是同一句话。
 */
export function validatePassword(_rule: unknown, value: string, callback: ValidateCallback): void {
  const v = typeof value === 'string' ? value : '';
  const ok =
    v.length >= 8
    && v.length <= 32
    && /[A-Za-z]/.test(v)
    && /[0-9]/.test(v)
    && !/\s/.test(v)
    && new Set(v).size > 1
    && !isSequentialString(v);
  callback(ok ? undefined : new Error(PASSWORD_HINT));
}

/**
 * 昵称/姓名规则（注册页的「姓名」与主页的「昵称」写的是同一列 `sys_user.nickname`）。
 *
 * 拦的是结构问题：长度、首尾空白、控制字符、零宽字符（可让同一个显示名看起来不同）、
 * 尖括号（避免把标签字符串带进日志与教师端导出）、以及整串同字符灌水。
 * 不做敏感词过滤——那需要专业词库，属产品口径（见 dev-docs/SECURITY_DECISIONS.md 的 SEC-6）。
 */
export function validateNickname(_rule: unknown, value: string, callback: ValidateCallback): void {
  const v = typeof value === 'string' ? value : '';
  const trimmed = v.trim();
  const hasBadChar = /[\p{Cc}\u200B-\u200F\uFEFF<>]/u.test(v);
  const tooRepetitive = /(.)\1{4,}/.test(v);
  const ok =
    trimmed.length >= 1
    && trimmed.length <= 30
    && trimmed === v
    && !hasBadChar
    && !tooRepetitive;
  callback(ok ? undefined : new Error(NICKNAME_HINT));
}
