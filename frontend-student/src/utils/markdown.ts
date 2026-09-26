/**
 * Markdown 安全渲染管线（C2.2）
 *
 * 抽成独立模块（而不是写死在 MarkdownViewer.vue 里）的原因：
 *  - 可被测试直接引用：XSS 过滤这条验收标准必须能拿真实输入断言，不能靠肉眼；
 *  - MarkdownViewer（AI 回答）与 KnowledgePanel（知识点精解，C2.5）共用同一套规则，
 *    避免两处净化策略漂移。
 *
 * 三道防线：
 *  1. markdown-it `html: false` —— 原始 HTML 标签一律转义成纯文本；
 *  2. DOMPurify 白名单净化 —— 兜住渲染结果里的危险标签/属性（如 javascript: 链接）；
 *  3. 白名单只管渲染，组件里的复制按钮走 DOM 后处理，不写内联事件。
 *
 * 不做数学公式（不引 KaTeX/MathJax，超出冻结范围 —— MEMBER_C_DEV_GUIDE 第一节）。
 */
import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';
// 只引 hljs/core + 按需注册语言。
// 直接 `import hljs from 'highlight.js'` 会把近 200 种语言全打进产物：
// 实测 ChatWorkspace 分包从 3.4KB 涨到 1077KB，直接把首屏拖慢——本项目是计算机课程，
// 下面这些语言足够覆盖课件内容与模型输出（各自自带的别名如 js/ts/html/sh/py/c++ 一并生效）。
import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import c from 'highlight.js/lib/languages/c';
import cpp from 'highlight.js/lib/languages/cpp';
import csharp from 'highlight.js/lib/languages/csharp';
import css from 'highlight.js/lib/languages/css';
import go from 'highlight.js/lib/languages/go';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import python from 'highlight.js/lib/languages/python';
import shell from 'highlight.js/lib/languages/shell';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

hljs.registerLanguage('bash', bash);
hljs.registerLanguage('c', c);
hljs.registerLanguage('cpp', cpp);
hljs.registerLanguage('csharp', csharp);
hljs.registerLanguage('css', css);
hljs.registerLanguage('go', go);
hljs.registerLanguage('java', java);
hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('json', json);
hljs.registerLanguage('python', python);
hljs.registerLanguage('shell', shell);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('typescript', typescript);
hljs.registerLanguage('xml', xml);
hljs.registerLanguage('yaml', yaml);

/**
 * 代码块在「fence 没写语言」或「语言未注册」时的纯文本转义。
 *
 * 为什么不直接用 `md.utils.escapeHtml`（markdown-it 官方用法）：
 * 那会在 `md` 的初始化表达式内部反引 `md` 自己，触发 TS 的循环类型推断
 * （TS7022/TS7023），导致 `md` 被判成 any 而整份渲染管线失去类型保护。
 * 这里的规则与 markdown-it 内部实现逐字符一致（& < > " 四个字符）。
 */
const HTML_ESCAPE_RE = /[&<>"]/g;
const HTML_ESCAPE_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
};

function escapeHtml(text: string): string {
  return text.replace(HTML_ESCAPE_RE, (ch) => HTML_ESCAPE_MAP[ch]);
}

/**
 * markdown-it 实例只建一次（模块级常量）。
 * 流式回答每帧都会重新渲染 Markdown，绝不能每帧 new 一个 MarkdownIt —— 那是纯浪费。
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  // 与展示稿一致：单个换行即换行（模型输出常按行排版）
  breaks: true,
  highlight(code: string, lang: string): string {
    /**
     * 返回「完整的 <pre> 块」而不是只有内部 HTML。
     * markdown-it 的约定：返回值以 `<pre` 开头就原样采用；否则它会自己包一层
     * `<pre><code class="language-x">`，那样就带不上 `hljs` 类 ——
     * 而 hljs 主题的底色与基础行高挂在 `.hljs` 上，漏了这个类样式只生效一半。
     */
    const language = lang && hljs.getLanguage(lang) ? lang : '';
    const inner = language
      ? hljs.highlight(code, { language, ignoreIllegals: true }).value
      : escapeHtml(code);
    const langClass = language ? ` language-${language}` : '';
    return `<pre class="hljs"><code class="hljs${langClass}">${inner}</code></pre>`;
  },
});

/** 允许出现在输出里的标签：恰好覆盖 markdown-it + hljs 的产出，白名单外一律丢弃 */
const ALLOWED_TAGS = [
  'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'blockquote',
  'ul', 'ol', 'li', 'code', 'pre', 'span',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'a', 'img',
];

/** 允许的属性：不含任何 on* 事件属性，也不含 style（避免 CSS 注入式钓鱼） */
const ALLOWED_ATTR = ['class', 'href', 'title', 'target', 'rel', 'src', 'alt'];

/**
 * 外链统一新开标签页并带 noopener/noreferrer：
 * 同标签跳走会丢掉当前问答现场，而 target=_blank 不带 rel 有 tabnabbing 风险。
 *
 * 类型从 `md.renderer.rules['link_open']` 反查（而非 import 深路径类型），
 * 这样签名永远跟着实际规则表走；显式标注也让两个箭头函数的参数有上下文类型。
 */
type LinkOpenRule = NonNullable<(typeof md.renderer.rules)['link_open']>;

const defaultLinkOpen: LinkOpenRule =
  md.renderer.rules.link_open ??
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options));

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank');
  tokens[idx].attrSet('rel', 'noopener noreferrer');
  return defaultLinkOpen(tokens, idx, options, env, self);
};

/**
 * 把 Markdown 文本渲染为可安全 `v-html` 的 HTML。
 * @param content 模型返回的 Markdown 原文（可能为空、可能在流式中途）
 */
export function renderMarkdownSafely(content: string): string {
  const rawHtml = md.render(content || '');
  return DOMPurify.sanitize(rawHtml, { ALLOWED_TAGS, ALLOWED_ATTR });
}
