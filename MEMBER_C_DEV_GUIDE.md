# 成员 C 详细开发文档：学生端智能答疑前台工程师

> **角色**：成员 C（学生前台核心开发工程师 · 前端工程师）  
> **职责模块**：学生前台问答工作台、SSE 打字机流式通讯、Markdown 与代码高亮渲染、知识库溯源抽屉、知识点解析展示  
> **适用技术栈**：Vue 3 (Composition API) + Vite + TypeScript + Pinia + Element Plus + Tailwind CSS + Highlight.js + DOMPurify

---

## 一、 模块定位与工程职责边界

成员 C 是学生用户的**“第一视觉与交互体验守门人”**，负责将复杂的 RAG 检索和大模型推理以丝滑、可信、直观的界面呈现给学生：

1. **学生工作台框架搭建**：实现响应式布局，左侧为课程切换与历史会话导航列表，中央为主问答流，右侧为出处溯源抽屉。
2. **SSE 流式通讯接收器**：封装 `fetch-event-source` 或标准 Fetch ReadableStream，精确监听解析 4 类后端事件（`references` -> `message` -> `done` -> `error`），实现零卡顿的流式打字机吐字效果。
3. **Markdown 与公式代码高亮渲染**：渲染大模型返回的复杂排版，支持代码一键复制、表格展示、防 XSS 注入净化。
4. **知识库出处溯源侧边抽屉 (Grounding Drawer)**：当回答中出现课件引用或者首包送达 `references` 时，以卡片形式展示命中课件文件名、相似度分数与原文片段，点击可展开高亮。
5. **知识点深度解析展示**：支持一键触发知识点精解，以 Markdown 展示核心概念定义与难点辨析（**不含自测题**，不做答题交互）。
6. **交互反馈闭环**：实现每条回答底部的点赞、点踩、重新生成与复制回答功能。

---

## 二、 前端项目依赖与安装 (`package.json`)

```json
{
  "dependencies": {
    "vue": "^3.4.21",
    "vue-router": "^4.3.0",
    "pinia": "^2.1.7",
    "element-plus": "^2.6.1",
    "@element-plus/icons-vue": "^2.3.1",
    "@microsoft/fetch-event-source": "^2.0.1",
    "markdown-it": "^14.1.0",
    "highlight.js": "^11.9.0",
    "dompurify": "^3.0.9",
    "axios": "^1.6.8"
  },
  "devDependencies": {
    "@types/markdown-it": "^13.0.7",
    "@types/dompurify": "^3.0.5",
    "typescript": "^5.2.2",
    "vite": "^5.1.6"
  }
}
```

---

## 三、 模块目录结构

```text
src/
├── api/
│   ├── course.ts              // 课程列表接口
│   └── qa.ts                  // 会话与反馈接口
├── components/
│   ├── ChatBubble.vue         // 单条问答消息气泡 (支持学生提问与AI回复)
│   ├── MarkdownViewer.vue     // Markdown与代码高亮清洗渲染器
│   ├── GroundingDrawer.vue    // 右侧课件出处溯源抽屉
│   └── KnowledgePanel.vue     // 知识点解析展示面板 (Markdown 渲染，只读)
├── stores/
│   ├── chatStore.ts           // 当前会话、流式消息与参考出处Pinia状态
│   └── courseStore.ts         // 当前选中课程状态
├── utils/
│   └── sseClient.ts           // 核心SSE流式请求封装 (含中断控制器)
└── views/student/
    ├── StudentLayout.vue      // 学生端主布局 (左侧栏+主操作区)
    └── ChatWorkspace.vue      // 智能答疑工作台核心视图
```

---

## 四、 核心功能代码实现指南

### 4.1 核心 SSE 流式客户端封装 (`src/utils/sseClient.ts`)
**实现要点**：必须持有 `AbortController`，在用户点击“停止生成”或切换会话时随时中断请求，杜绝内存泄漏和错位渲染。

```typescript
import { fetchEventSource } from '@microsoft/fetch-event-source';

export interface SseReference {
  docId: number;
  fileName: string;
  chunkIndex: number;
  score: number;
  snippet: string;
}

export interface SseCallbacks {
  onReferences: (refs: SseReference[]) => void;
  onToken: (token: string) => void;
  onDone: (data: any) => void;
  onError: (err: any) => void;
}

export class SseChatClient {
  private abortController: AbortController | null = null;

  public async startStream(
    courseId: number,
    sessionId: number,
    question: string,
    callbacks: SseCallbacks
  ) {
    // 中断先前的请求
    this.stopStream();
    this.abortController = new AbortController();

    const token = localStorage.getItem('satoken') || ''; // 键名统一为 satoken，全团队一致
    const url = `/api/qa/chat/stream?courseId=${courseId}&sessionId=${sessionId}&question=${encodeURIComponent(question)}`;

    try {
      await fetchEventSource(url, {
        method: 'GET',
        headers: {
          // 两个头必须同时发：satoken 是 Sa-Token 唯一可靠识别来源，
          // 只发 Authorization 会被判定未登录（401）。详见 AGENT_INSTRUCTIONS 1.2 鉴权头铁律。
          'satoken': token,
          'Authorization': `Bearer ${token}`,
          'Accept': 'text/event-stream',
        },
        signal: this.abortController.signal,
        openWhenHidden: true,

        onmessage(msg) {
          // 处理 4 类事件（后端所有 data 均为 JSON，与 DEV_SPECIFICATION 4.2 严格对齐）
          if (msg.event === 'references') {
            const refs = JSON.parse(msg.data) as SseReference[];
            callbacks.onReferences(refs);
          } else if (msg.event === 'message') {
            // message 包为增量 JSON: {"delta": "..."}，解析失败则降级按裸文本处理
            try {
              callbacks.onToken(JSON.parse(msg.data).delta ?? '');
            } catch {
              callbacks.onToken(msg.data);
            }
          } else if (msg.event === 'done') {
            // done 包携带 recordId/sessionId，必须保存 recordId 供点赞/点踩使用
            const doneInfo = JSON.parse(msg.data);
            callbacks.onDone(doneInfo);
          } else if (msg.event === 'error') {
            callbacks.onError(msg.data);
          }
        },

        onerror(err) {
          callbacks.onError(err);
          throw err; // 防止默认重试
        }
      });
    } catch (error: any) {
      if (error.name !== 'AbortError') {
        callbacks.onError(error);
      }
    }
  }

  public stopStream() {
    if (this.abortController) {
      this.abortController.abort();
      this.abortController = null;
    }
  }
}
```

### 4.2 Markdown 安全高亮渲染器 (`src/components/MarkdownViewer.vue`)
```vue
<template>
  <div class="markdown-body text-sm leading-relaxed" v-html="sanitizedHtml"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import MarkdownIt from 'markdown-it';
import hljs from 'highlight.js';
import 'highlight.js/styles/github.css';
import DOMPurify from 'dompurify';

const props = defineProps<{
  content: string;
}>();

const md = new MarkdownIt({
  html: false, // 禁用原始 HTML 标签防止注入
  linkify: true,
  typographer: true,
  highlight: function (str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs font-mono p-3 my-2 rounded-lg bg-slate-900 text-slate-100 overflow-x-auto text-xs"><code>${
          hljs.highlight(str, { language: lang, ignoreIllegals: true }).value
        }</code></pre>`;
      } catch (__) {}
    }
    return `<pre class="hljs font-mono p-3 my-2 rounded-lg bg-slate-900 text-slate-100 overflow-x-auto text-xs"><code>${md.utils.escapeHtml(str)}</code></pre>`;
  }
});

const sanitizedHtml = computed(() => {
  const rawHtml = md.render(props.content || '');
  return DOMPurify.sanitize(rawHtml);
});
</script>
```

### 4.3 课件出处溯源抽屉 (`src/components/GroundingDrawer.vue`)
```vue
<template>
  <el-drawer
    v-model="visible"
    title="课件出处知识库溯源"
    direction="rtl"
    size="380px"
  >
    <div v-if="references.length === 0" class="text-slate-400 text-center py-8 text-xs">
      本次回答未引用或暂无相关参考切块
    </div>

    <div v-else class="space-y-4">
      <div
        v-for="(ref, index) in references"
        :key="index"
        class="border border-slate-200 rounded-lg p-3.5 bg-slate-50 hover:bg-white hover:border-indigo-300 transition-all shadow-2xs"
      >
        <div class="flex items-center justify-between mb-2">
          <span class="inline-flex items-center text-xs font-semibold text-indigo-700 bg-indigo-50 px-2 py-0.5 rounded border border-indigo-100">
            [引用 {{ index + 1 }}]
          </span>
          <span class="text-[11px] text-slate-500 font-mono">
            相似度: {{ (ref.score * 100).toFixed(1) }}%
          </span>
        </div>

        <div class="text-xs font-bold text-slate-800 flex items-center gap-1 mb-1.5 truncate">
          📄 {{ ref.fileName }}
        </div>

        <p class="text-xs text-slate-600 bg-white p-2.5 rounded border border-slate-100 leading-relaxed font-sans line-clamp-4 hover:line-clamp-none">
          {{ ref.snippet }}
        </p>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import type { SseReference } from '../utils/sseClient';

const visible = ref(false);
const references = ref<SseReference[]>([]);

const open = (refs: SseReference[]) => {
  references.value = refs;
  visible.value = true;
};

defineExpose({ open });
</script>
```

---

## 五、 协同契约与交付物清单

### 5.1 对接配合要求
1. **对接成员 A（AI 算法）**：
   - 调试 `GET /api/qa/chat/stream`，保证打字机首字延迟（TTFT）在 1.5 秒以内。
   - 监听 `references` 事件，一旦到达立即激活聊天气泡上的“已溯源 3 处课件”徽章。
2. **对接成员 B（后端业务）**：
   - 调用 `POST /api/auth/login` 保存 Token 至 `localStorage`（键名统一 `satoken`）。
   - 课程切换调用 `GET /api/course/list`；历史会话调用 `GET /api/qa/sessions` 与 `GET /api/qa/records`。
   - 提问结束点击点赞/点踩，调用 `POST /api/qa/records/{id}/feedback`。
3. **知识点解析**：
   - 调用 `POST /api/knowledge/generate` 获取知识点精解内容，用 `MarkdownViewer` 渲染展示（**只读，不做答题交互**）。

### 5.2 成员 C 验收与交付物自测表
- [ ] 流式打字过程中，滚动条能够自动跟随消息高度平滑下滚。
- [ ] 点击“停止生成”按钮时，SSE 连接立即中断，界面停止输出且不抛未捕获异常。
- [ ] 代码块高亮显示正确，且具备一键复制到剪贴板功能。
- [ ] 点击回答下方的“参考出处”能展开右侧抽屉，显示清晰的课件文件名与命中文字。
