# 教师端管理后台（成员 D）

> 所属项目：AI 驱动的在线学习智能答疑辅导平台（RAG 课程知识库）
> 角色：成员 D —— 教师后台前端 + 全链路质检 + 答辩材料统筹
> 本目录 = 教师端独立前端工程（Vue 3 + Vite 5 + TypeScript + Element Plus + Pinia + Axios）
> 工程位置：**Monorepo 仓库的 `frontend-teacher/` 子目录**（Q1 拍板，组长 A 2026-09-13）
> CI 门禁：`.github/workflows/ci.yml` 中的 `frontend-teacher` job（`npm ci` → `vue-tsc --noEmit` → `npm run build`）

---

## 一、当前进度：第 1 周（Day 1~7）

| 编号 | 任务 | 状态 | 验收标准 | 证据 |
| :--- | :--- | :--- | :--- | :--- |
| D1.1 | 后台脚手架 | 完成 | `npm run dev` 能打开 | dev 服务器 `http://localhost:5173/` 返回 HTTP 200 |
| D1.2 | 后台布局 + 路由 + 角色守卫 | 完成 | 学生账号访问后台被拒 | 路由守卫拦截 + 登录页角色校验；见第四节自测 |
| D1.3 | 课件管理页（表格 + 上传入口，先 Mock） | 完成 | 用 Mock 数据能渲染表格 | `CourseDocManage.vue` 渲染 3 条 Mock 课件（含 CHUNKED / PARSING / FAILED 三态） |
| D1.4 | 课件上传界面（拖拽 + 进度条，先 Mock） | 完成 | 能选择文件并显示进度（不真正上传） | `DocUploadModal.vue` Mock 通道本地模拟进度 0→100% |
| D1.5 | 准备 3~5 篇测试课件 PDF | 完成 | 放入共享盘，全员可取 | 4 篇 PDF 共 26.4 KB，中文文本可提取（保留在本地 `项目/D/test-courseware/`，**不入库**，改为发群共享） |

## 一·二、第 2 周进度（Day 8~14）

| 编号 | 任务 | 状态 | 验收标准 | 证据 |
| :--- | :--- | :--- | :--- | :--- |
| D2.1 | 对接真实上传/列表/删除接口（Issue #21） | 完成 | 真实 PDF 上传后端收到并触发切块；删除后列表消失 | 见下方「D2.1 联调实测」 |

> **D2.1（2026-09-17）：解除 Mock，接口结构按 Q7~Q13 定稿**（依据 `dev-docs/mock/B回复-接口确认单(Q7-Q13).md`）
> - `USE_MOCK = false`；删除 `src/mock/` 整目录；Mock 分支从 `api/auth.ts`、`api/course.ts`、`api/teacher.ts`、`DocUploadModal.vue`、`LoginView.vue` 全部移除
> - `types/index.ts`：`LoginResult` 补 `username` / `avatarUrl`；`CourseDoc` 补 `courseId` / `fileSize` / `updatedAt`；**故意不声明 `filePath` 与 `isDeleted`**（Q8：`filePath` 属信息暴露点、前端不展示）
> - 静态验证：`npx vue-tsc --noEmit` 退出码 0；`npm run build` 退出码 0（✓ built in 7.34s）
> - **联调实测**（本机后端 `http://localhost:8080`，种子账号 teacher01）：
>   1. `POST /api/auth/login` → 200，六字段 `token/userId/username/nickname/role/avatarUrl`（与 Q7 一致）
>   2. `GET /api/teacher/docs/list?courseId=1` → 200，**裸数组**；字段含 `filePath`/`isDeleted`（前端不声明不展示）；`errorMsg` 为空串
>   3. `POST /api/teacher/docs/upload`（courseId=1 + 真实 PDF）→ 200，`data` = 新 docId
>   4. 上传后立刻拉列表 → `PARSING`；约 12 秒后 → `CHUNKED`（`chunkCount=6`）
>   5. `DELETE /api/teacher/docs/{id}` → 200，`data=true`，列表回到原状
> - 该链路**不需要 LLM API Key**：切块用内置 BGE-Small-ZH 本地向量模型，Chroma 未启动时自动降级内存存储（`application.yml` 63-64 行）

> **Day 1 下午追加：落实组长 A 对 Q1 / Q17 的拍板**> - **Q1 已落地**：工程迁入 Monorepo 的 `frontend-teacher/` 子目录（27 个文件），`ci.yml` 新增 `frontend-teacher` 门禁 job。验证：`npm ci`（99 packages）→ `npx vue-tsc --noEmit` 退出码 0 → `npm run build` ✓ 1685 modules / 9.19s，退出码 0。
> - **Q17 已落地**：`CourseDocManage.vue` 加轮询兜底——3 秒轮询 + **最长 2 分钟自动停** + 页面「刷新」按钮（手动刷新与上传成功都会重置 2 分钟窗口），超时展示 `el-alert` 告示条。
> - **Q7~Q13 待成员 B**：已在 `src/types/index.ts`、`src/api/teacher.ts` 标 `TODO` 注明当前口径，不阻塞本周。

---

## 二、如何运行

```bash
npm ci               # 按 package-lock.json 精确安装（与 CI 一致，推荐）
# 或（无 lockfile 变更时）
npm install

npm run dev          # 打开 http://localhost:5173
```

验证命令（README 纪律 2：两条都要跑，顺序不能反）：

```bash
npx vue-tsc --noEmit     # 先跑：能查出"模板里调用了不存在的方法"
npm run build            # 后跑（内部已含 vue-tsc）
```

### Mock 阶段可用账号

| 账号 | 密码 | 角色 | 用途 |
| :--- | :--- | :--- | :--- |
| `teacher01` | `123456` | TEACHER | 进入教师后台 |
| `student01` | `123456` | STUDENT | 验证角色守卫：会被拒绝并提示"无权访问教师管理后台" |

> Mock 模式下无需后端即可完整体验：登录 → 课件列表 → 上传进度 → 状态 3 秒轮询（PARSING 会自动转 CHUNKED）→ 删除。

---

## 三、目录结构

```text
src/
├── api/                    # 接口请求函数（auth / course / teacher）
├── config/                 # USE_MOCK 全局开关
├── mock/                   # Week1 Mock 数据（D2.1 对接真实接口后删除）
├── router/                 # 路由 + 角色守卫
├── stores/                 # Pinia（user / course）
├── styles/global.css       # reset + 主题变量（主色 Indigo #4F46E5）
├── types/                  # 全局类型（状态机四态、接口返回结构）
├── utils/request.ts        # axios 统一封装（自动带 Authorization 头、解包 Result）
└── views/
    ├── auth/LoginView.vue
    └── teacher/
        ├── TeacherLayout.vue
        ├── CourseDocManage.vue
        └── components/DocUploadModal.vue
```

---

## 四、第一周验收自测结果

| 验证项 | 命令 / 操作 | 结果 |
| :--- | :--- | :--- |
| 类型检查 | `npx vue-tsc --noEmit` | 无任何输出（0 错误） |
| 生产构建 | `npm run build` | `✓ 1685 modules transformed. ✓ built in 6.14s`，退出码 0 |
| 开发服务器 | `npm run dev` + `curl http://localhost:5173/` | HTTP 200，返回页面标题"智能答疑平台 · 教师管理后台" |
| 路由守卫（未登录） | 直接访问 `/teacher/docs` | 重定向到 `/login?redirect=/teacher/docs` |
| 路由守卫（学生） | 用 `student01` 登录 | 提示"无权访问教师管理后台"，不写入会话，停留在登录页 |
| 路由守卫（教师） | 用 `teacher01` 登录 | 跳转 `/teacher/docs`，左侧菜单与面包屑正常 |
| 表格渲染（Mock） | 打开课件管理页 | 3 行数据；状态列分别渲染 `已就绪` / `切块向量化中` / `失败(查看原因)` |
| 状态轮询 | 停留在课件管理页 | 6 秒后 `PARSING` 自动变为 `CHUNKED`，切块数由 0 变为随机值 |
| 上传进度（Mock） | 拖拽 `pdf/docx/md/txt` 文件到弹窗 | 进度条 0→100%，完成后列表顶部新增一条 `PARSING` 记录 |
| 上传类型校验（PR #27 评审修复） | 拖拽白名单外的文件（如 `.exe`） | `beforeUpload` 拦截并提示"仅支持 PDF / DOCX / Markdown / TXT 格式的文件"，不进入上传流程（`accept` 只约束点击选择器，拖拽由白名单兜底） |

> 说明：以上为代码层面的自测结论，浏览器人工复核请在 `npm run dev` 后按"操作"列执行。

---

## 五、与文档的偏离说明（均为主动记录，非默认行为）

| # | 文档要求 | 实际实现 | 理由 |
| :--- | :--- | :--- | :--- |
| 1 | 登录页属于成员 C（C1.4） | 本工程实现了最小可用的 `LoginView.vue` | 角色守卫（D1.2）必须有一个登录入口才能自测。**Q2 已拍板：教师端独立工程成立，本页保留**（组长 A 2026-09-13） |
| 2 | 学生越权后重定向到 `/student/chat` | 改为清除会话 + 回 `/login` | `/student/chat` 属于 C 的学生端工程，本工程不存在该路由，直接跳转会导致 404 与守卫循环 |
| 3 | `MEMBER_D_DEV_GUIDE.md` 示例代码使用 Tailwind 类名（`p-6`、`text-slate-900` 等） | 改用 `<style scoped>` 原生 CSS | Tailwind 不在本项目 `package.json` 依赖白名单内（`DEV_SPECIFICATION.md` 3.2 允许二选一） |
| 4 | 后台含"课件知识库管理"与"问答记录查看"两个子系统 | 本周只挂"课件知识库管理"一个菜单 | 问答记录页是 D2.3 的任务，按"一次只做一个任务"的纪律不在第 1 周提前实现 |
| 5 | 课件上传按示例用 `action` + `:headers` 手动补鉴权头 | 保留该真实通道；Mock 阶段改用 `http-request` 走本地模拟 | Mock 阶段不存在后端上传端点，`action` 会直接报错，无法满足 D1.4"能选择文件并显示进度"的验收标准 |

---

## 六、待确认项（**不猜，等确认后再改**）

| # | 问题 | 当前处理 | 需要谁确认 |
| :--- | :--- | :--- | :--- |
| 1 | 登录接口返回的字段名是否确为 `token` / `role` | 按字面使用 `token` / `role`，另预留 `nickname` / `userId` | 成员 B（Knife4j 定义出来后对齐） |
| 2 | `role` 存 localStorage 的键名文档未约定 | 暂用 `role` | 组长 / 成员 C（共用工程时需统一） |
| 3 | Pinia 是否属约定依赖 | 已引入 `pinia ^2.1.7`（依据：D 指南技术栈行 + `DEV_SPECIFICATION.md` 3.1 的 `stores/` 标准），但 `MEMBER_D_DEV_GUIDE.md` 的 `package.json` 未列出，版本号也未锁定 | 组长 |
| 4 | 学生越权后的跳转目标 | 见第五节第 2 条 | 组长 |
| 5 | 独立登录页的去留 | 见第五节第 1 条 | 组长 / 成员 C |
| 6 | 课件列表是否需要"重建索引"按钮 | 本周未加（属于 D2.4） | —— |

---

## 七、D2.1 对接真实接口时的清理清单

`USE_MOCK` 目前为 `true`（见 `src/config/index.ts`）。对接真实接口时必须完成：

1. `src/config/index.ts`：`USE_MOCK` 改为 `false`
2. 删除整个 `src/mock/` 目录
3. 删除以下文件中残留的 Mock import 与分支（共 4 处）：
   - `src/api/auth.ts` → `mockLogin`
   - `src/api/course.ts` → `mockCourses`
   - `src/api/teacher.ts` → `mockListDocs`、`mockRemoveDoc`
   - `src/views/teacher/components/DocUploadModal.vue` → `mockCreateDoc`、`mockUpload`、`uploadBindings` 的 Mock 分支
4. 删除登录页的 Mock 提示块（`v-if="USE_MOCK"` 的 `el-alert`）
5. 删除 `src/config/index.ts` 本身

> 验收口径：`grep -r "USE_MOCK\|@/mock" src/` 无任何结果。

---

## 八、测试课件数据集（D1.5）

位于 `test-courseware/`，共 4 篇，内容为计算机专业教材章节风格，用于走通"上传 → 解析切块 → 向量化 → 检索命中 → 引用出处"全链路：

| 文件 | 对应课程 | 主要内容 |
| :--- | :--- | :--- |
| 第3章 内存管理.pdf | 计算机操作系统原理与实践（CS202401） | 分页、快表、多级页表、虚拟内存、页面置换、工作集 |
| 第4章 进程调度与死锁.pdf | 计算机操作系统原理与实践（CS202401） | 调度算法、死锁四条件、银行家算法、检测与解除 |
| 第5章 文件系统与磁盘管理.pdf | 计算机操作系统原理与实践（CS202401） | 文件物理结构、inode 混合索引、空闲空间管理、磁盘调度 |
| 第2章 应用层协议与拥塞控制.pdf | 计算机网络与高并发通信（CS202402） | TCP/IP 分层、握手挥手、拥塞控制、DNS、HTTP/HTTPS |

- 每篇约 1500~1900 字，按 200~400 字/段组织，便于观察 `chunk_size=400 / overlap=50` 的切块效果。
- 中文文本已验证可被 PDF 解析器正常提取（`pdfbox`/`tika` 侧切块不会取到空文本）。
- 重新生成：`python test-courseware/_generate_courseware.py`
- **待办**：把这 4 篇放入团队共享盘，保证 A/B/C 三人都能取到。

---

## 九、执行纪律

写代码前请先阅读工作区根目录的 `D-AI约束.md`（防幻觉 / 防偏航约束）与 `D任务.md`（任务清单）。要点：

1. 只做文档里写的任务，AI 提的"加个功能"一律拒绝。
2. AI 说"完成了"不算数，`vue-tsc` + `build` 两条命令跑过才算数。
3. 接口字段不许猜，不确定就停下问，不要先写占位符。
