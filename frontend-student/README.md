# 学生端问答工作台（成员 C）

> 所属项目：AI 驱动的在线学习智能答疑辅导平台（RAG 课程知识库）
> 角色：成员 C —— 学生端前台核心开发（学生交互 + SSE 流式 + Markdown 渲染 + 出处溯源）
> 本目录 = 学生端独立前端工程（Vue 3 + Vite 5 + TypeScript + Element Plus + Pinia + Axios）
> 工程位置：**Monorepo 仓库的 `frontend-student/` 子目录**
> CI 门禁：`.github/workflows/ci.yml` 中的 `frontend-student` job（`npm ci` → `vue-tsc --noEmit` → `npm run build`）

---

## 一、当前进度：第 1 周（Day 1~7）

| 编号 | 任务 | 状态 | 验收标准 | 证据 |
| :--- | :--- | :--- | :--- | :--- |
| C1.1 | Vue3 + Vite + Element Plus 脚手架 | 完成 | `npm run dev` 打开有页面 | 见第五节：dev 服务器 `http://localhost:5174/` 返回 HTTP 200，标题「智能答疑平台 · 学生问答工作台」 |
| C1.2 | 请求层 `request.ts`（token 拦截器） | 完成 | 请求自动带 `Authorization: Bearer <token>`；401 跳登录 | `src/utils/request.ts`：请求拦截器注入头（含 `Bearer ` 前缀）；响应拦截器解包 `Result<T>`，401 清三键并跳 `/login?redirect=` |
| C1.3 | `sseClient.ts` 骨架（先按契约写） | 完成 | 能通过 `event:` 区分 4 类事件并打印到 console | `src/utils/sseClient.ts`：`switch (msg.event)` 分支 `references`/`message`/`done`/`error`，每帧 `console.debug` 打印事件名与载荷 |
| C1.4 | 登录页 + 路由守卫 | 完成 | 用种子账号能登录并跳转；未登录访问被拦截 | `views/auth/LoginView.vue` + `router/index.ts` 全局守卫；见第五节自测 |
| C1.5 | 主页布局（课程切换 + 侧边会话栏） | 完成 | 页面结构完整，数据用 Mock | `views/student/StudentLayout.vue`（课程切换 + 历史会话栏 + 用户区）+ `ChatWorkspace.vue`（头部 + 消息区 + 输入区） |

> **本周只做布局骨架，不抢跑。** 打字机流式（C2.1）、Markdown/代码高亮（C2.2）、
> 出处抽屉（C2.3）、点赞/点踩（C2.6）属第 2 周任务，本周**未实现**：输入框为
> `disabled` 占位，不做假交互；消息先按纯文本渲染。

---

## 二、如何运行

```bash
npm ci               # 按 package-lock.json 精确安装（与 CI 一致，推荐）
# 或（无 lockfile 变更时）
npm install

npm run dev          # 打开 http://localhost:5174
```

> **端口说明**：学生端 dev 端口为 **5174**（不是模板里的 5173）。同一 Monorepo 内
> `frontend-teacher/` 已占用 5173，两个前端共用端口会 `EADDRINUSE`。该端口不在
> `AGENT_INSTRUCTIONS.md` 2.0「三处不得改动」之列（那三处是 `plugins: [vue()]`、
> 代理路径 `/api`、代理 target 端口 8080，均已严格保持一致）。

验证命令（README 纪律 2：两条都要跑，顺序不能反）：

```bash
npx vue-tsc --noEmit     # 先跑：能查出"模板里调用了不存在的方法"
npm run build            # 后跑（内部已含 vue-tsc）
```

### Mock 阶段可用账号

| 账号 | 密码 | 角色 | 用途 |
| :--- | :--- | :--- | :--- |
| `student01` | `123456` | STUDENT | 进入学生问答工作台（李明） |
| `student02` | `123456` | STUDENT | 另一名学生（张华） |
| `teacher01` | `123456` | TEACHER | 验证角色守卫：会被拒绝并提示"请使用学生账号登录" |

---

## 三、目录结构

```text
src/
├── api/                    # 接口请求函数（auth / course / qa）
├── config/                 # USE_MOCK 全局开关
├── mock/                   # Week1 Mock 数据（对接真实接口后删除）
├── router/                 # 路由 + 全局守卫
├── stores/                 # Pinia：userStore / courseStore / chatStore
├── styles/global.css       # reset + 主题变量（主色 Indigo #4F46E5）
├── types/                  # 全局类型（登录返回、SSE 四事件载荷、参考资料五字段）
├── utils/
│   ├── request.ts          # axios 统一封装（Authorization: Bearer 头、解包 Result）
│   └── sseClient.ts        # SSE 流式客户端（4 类事件 + AbortController）
└── views/
    ├── auth/LoginView.vue
    └── student/
        ├── StudentLayout.vue   # 主布局：课程切换 + 历史会话栏
        └── ChatWorkspace.vue   # 智能答疑工作台（布局骨架）
```

---

## 四、遵循的契约（唯一真源：`DEV_SPECIFICATION.md` 第 4.2 节）

| 项 | 取值 | 落地位置 |
| :--- | :--- | :--- |
| SSE 路径 | `GET /api/qa/chat/stream?courseId=&sessionId=&question=` | `utils/sseClient.ts` 的 `SSE_CHAT_PATH` |
| SSE 事件 | 只有 4 种：`references` / `message` / `done` / `error`，**data 全为 JSON** | `utils/sseClient.ts` 的 `switch (msg.event)` |
| `message` 载荷 | `{"delta": "..."}` | 同上（解析失败降级按裸文本，兼容保护） |
| `done` 载荷 | `{"recordId":1024,"sessionId":7,...}`，**必含 recordId** | 同上，`recordId` 落进 `ChatMessage.recordId` 供 C2.6 点赞/点踩 |
| 鉴权头 | `Authorization: Bearer <token>`（**须带 `Bearer ` 前缀含空格**） | `utils/request.ts` 与 `utils/sseClient.ts` 两处 |
| Token 存储 | `localStorage` 键名 `satoken` | `stores/userStore.ts` |
| 会话懒创建 | `sessionId` 传 `0` 时不预建会话，由后端创建并在 `done` 回传真实 `sessionId` | `stores/chatStore.ts`（新建会话置 0） |
| 参考资料字段 | 五字段冻结：`docId`/`fileName`/`chunkIndex`/`score`/`snippet` | `types/index.ts` 的 `SseReference` |
| 课程列表 | 裸数组，字段 `courseName` | `api/course.ts` |
| 登录返回 | 解包后取 `token`/`role`/`nickname`/`userId` | `api/auth.ts` |

---

## 五、第一周验收自测结果

| 验证项 | 命令 / 操作 | 结果 |
| :--- | :--- | :--- |
| 依赖安装 | `npm install` | `added 114 packages in 8m`，无 warning，退出码 0 |
| 类型检查 | `npx vue-tsc --noEmit` | **无任何输出（0 错误）**，退出码 0 |
| 生产构建 | `npm run build` | `✓ 1684 modules transformed. ✓ built in 5.93s`，退出码 0 |
| 开发服务器 | `npm run dev` + 请求 `http://localhost:5174/` | `VITE v5.4.21 ready in 1061 ms` → HTTP **200**；页面含 `<title>智能答疑平台 · 学生问答工作台</title>`、`#app` 挂载点与 `/src/main.ts` 入口 |

> 构建有一条**提示（非错误）**：入口 chunk 1057 kB > 500 kB 的 Vite 体积警告。
> 原因为 Element Plus 全量引入（`app.use(ElementPlus)`）。属第 3 周 UI 打磨范畴，
> 本周不动（按需引入会牵动 C2.x 的组件使用方式）。

以下为**代码层面**结论，浏览器人工复核请在 `npm run dev` 后按"操作"列执行：

| 验证项 | 操作 | 预期 |
| :--- | :--- | :--- |
| 路由守卫（未登录） | 直接访问 `/student/chat` | 重定向到 `/login?redirect=/student/chat` |
| 登录跳转（学生） | 用 `student01` / `123456` 登录 | 跳转 `/student/chat`，左侧显示课程下拉与历史会话 |
| 角色守卫（教师） | 用 `teacher01` / `123456` 登录 | 提示"请使用学生账号登录"，**不写入会话**，停留登录页 |
| 课程切换 | 切换课程下拉 | 历史会话栏重新加载（换课程不串会话） |
| 会话切换 | 点击历史会话 | 消息区渲染该会话的历史问答（含"参考资料 (N 处)"标签） |
| 空状态 | 选择无历史会话的课程 | 显示"该课程暂无历史会话"；消息区显示 `el-empty` 空状态 |
| 退出登录 | 点左下角"退出" | 清 `satoken`/`role`/`nickname` 三键并回到 `/login` |

> 口径说明：`vue-tsc` / `build` / `dev` 三项为**真实执行过**的输出；
> 守卫与交互项为代码路径结论，未做浏览器端人工点击复核。

---

## 六、与文档的偏离说明（均为主动记录，非默认行为）

| # | 文档要求 | 实际实现 | 理由 |
| :--- | :--- | :--- | :--- |
| 1 | `AGENT_INSTRUCTIONS.md` 2.0 的 `vite.config.ts` 里 `server.port = 5173` | 改为 **5174** | 同仓 `frontend-teacher/` 已占用 5173，共用会 `EADDRINUSE`。该端口不在"三处不得改动"清单内（`plugins: [vue()]` / 代理路径 `/api` / target 8080 均已一致） |
| 2 | `MEMBER_C_DEV_GUIDE.md` 第 3 节：`stores/chatStore.ts`、`stores/courseStore.ts` | 照此命名；另新增 `stores/userStore.ts` | 登录态是 `request.ts` 401 处理与路由守卫的公共依赖，指南目录清单未列但必需。命名统一为 `*Store.ts`（与教师端 `stores/user.ts` 的文件名不同，属外观差异，键名已对齐） |
| 3 | `MEMBER_C_DEV_GUIDE.md` 4.2/4.3/4.1 的完整组件实现 | 本周**未实现** `MarkdownViewer.vue` / `GroundingDrawer.vue` / `KnowledgePanel.vue` / 打字机节流 | 分属 C2.2 / C2.3 / C2.5 / C2.1，按"一次只做一个任务"的纪律不提前实现。`sseClient.ts` 只做 C1.3 要求的"事件区分"，不做渲染 |
| 4 | 示例代码使用 Tailwind 类名（`p-3`、`text-slate-500` 等） | 改用 `<style scoped>` 原生 CSS | Tailwind 不在依赖白名单内（`MEMBER_C_DEV_GUIDE.md` 第四节样式说明已明确"不要为几行示例去装 Tailwind"） |
| 5 | 学生端工程目录名 | 采用 `frontend-student/` | 见第七节待确认项第 1 条：`ci.yml` 头部与门禁 4 注释对该目录名各有一处表述，本工程按"与 `frontend-teacher/` 对称"落地 |

---

## 七、待确认项（**不猜，等确认后再改**）

| # | 问题 | 当前处理 | 需要谁确认 |
| :--- | :--- | :--- | :--- |
| 1 | 学生端目录名：`frontend-student/` 还是沿用 `ci.yml` 门禁 3 的 `frontend/`？ | 采用 `frontend-student/`（与 `frontend-teacher/` 对称），并已按 `ci.yml` 头部第 1b 条与门禁 4 注释"照此再复制一份"新增 `frontend-student` job（纯新增，未改动原有 job） | 组长 A（若改回 `frontend/`，需撤掉新增 job，目录改名即可） |
| 2 | `role` / `nickname` 存 localStorage 的键名文档未约定 | 与 `frontend-teacher/` 保持 `role` / `nickname` | 组长 / 成员 D（两端已对齐，确认即可） |
| 3 | 登录返回字段名最终口径 | 依 B 回复确认单 Q7（已核对源码）：`token`/`role`/`nickname`/`userId` | 成员 B（Knife4j 为准，当前口径已确认） |
| 4 | `chatStore.loadSessions` 依赖的 `GET /api/qa/sessions` 返回结构（裸数组还是分页） | 暂按**裸数组** `QaSession[]` | 成员 B（B2.4 截止 Day 11） |
| 5 | 学生端根路由 `/:pathMatch(.*)*` 兜底到 `/student/chat` 是否可接受 | 已实现（避免 404 白屏） | 组长（无异议即保留） |

---

## 八、对接真实接口时的清理清单

`USE_MOCK` 目前为 `true`（见 `src/config/index.ts`）。对接真实接口时必须完成：

1. `src/config/index.ts`：`USE_MOCK` 改为 `false`
2. 删除整个 `src/mock/` 目录（`auth.ts` / `courses.ts` / `qa.ts`）
3. 删除以下文件中的 Mock import 与分支（共 3 处）：
   - `src/api/auth.ts` → `mockLogin`
   - `src/api/course.ts` → `mockListCourses`
   - `src/api/qa.ts` → `mockListSessions`、`mockListRecords`
4. 删除登录页的 Mock 提示块（`v-if="USE_MOCK"` 的 `el-alert`）
5. 删除 `src/config/index.ts` 本身

> 验收口径：`grep -r "USE_MOCK\|@/mock" src/` 无任何结果。

---

## 九、执行纪律

1. 只做文档里写的任务，AI 提的"加个功能"一律拒绝（功能范围见 `THREE_WEEK_PLAN.md` 第一节）。
2. AI 说"完成了"不算数，`npx vue-tsc --noEmit` + `npm run build` 两条命令跑过才算数。
3. 接口字段不许猜，不确定就停下问，不要先写占位符。
