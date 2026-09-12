# AI 驱动的在线学习智能答疑辅导平台（RAG 课程知识库）— 开发文档仓库

> 本仓库**只存放开发文档**，不存放代码。代码由每位组员在自己电脑上新建的工程目录中，由 AI Agent（Claude Code / Cursor / Windsurf / Qwen-Code / Codex 等）根据本仓库文档生成。
> 团队后端统一工程仓库（代码仓）另行创建，本仓库文档中的接口契约、表结构、配置模板是四个人的"共同法律"。

## 一、文档清单与阅读顺序

| 顺序 | 文件 | 给谁用 | 作用 |
| :--- | :--- | :--- | :--- |
| 0 | `GLOSSARY.md` | **零基础先看** | 术语表（人话版）、零经验 FAQ、常见报错速查表 |
| 1 | `THREE_WEEK_PLAN.md` | **全员先读** | **工期唯一权威**：三周冲刺计划、每人每周任务与验收标准、裁剪顺序 |
| 2 | `TEAM_WORK_DIVISION.md` | 全员先读 | 角色分工（第一章）、WBS 任务清单（第二章）、**接口矩阵（第三章）**；第四章原 5 周路线图已作废 |
| 3 | `DEV_SPECIFICATION.md` | 全员必读 | Git 规范、代码规范、**SSE 接口唯一契约**、环境基线 |
| 4 | `COLLABORATION_WORKFLOW.md` | 全员必读 | **决策权 RACI、分支保护、PR/Review 门禁、CI、完成的定义（DoD）、风险登记册** |
| 5 | `AGENT_INSTRUCTIONS.md` | 喂给 AI 的第一份文件 | Agent 禁令、application.yml 完整模板、线程池规范、冒烟验收清单 |
| 6 | `MEMBER_X_DEV_GUIDE.md` | 各自认领 | 成员 A（RAG/SSE）、B（业务/DB）、C（学生前端）、D（教师前端） |
| 7 | `ADVERSARIAL_AUDIT_REPORT.md` | 遇到问题时查 | 历史踩坑分析与答辩防御预案（缺陷已回写进上述文档，此处仅作背景） |

### 配套资产（需复制到代码仓生效）

| 路径 | 作用 |
| :--- | :--- |
| `.github/PULL_REQUEST_TEMPLATE.md` | PR 四段式描述 + DoD 自查 + Reviewer 检查表，自动填充 |
| `.github/ISSUE_TEMPLATE/task.yml` | 任务 Issue 模板（成员/周次/验收标准/依赖） |
| `.github/ISSUE_TEMPLATE/bug.yml` | 缺陷模板（含 P0~P3 定级与根因分析栏） |
| `.github/workflows/ci.yml` | CI 门禁：密钥扫描 → 后端 `mvn compile` → 前端 `vue-tsc` + `vite build` |
| `templates/application-example.yml` | 脱敏配置模板，复制为 `application-local.yml` 后填自己的值 |

> 这些文件放在**代码仓**才会自动生效；本仓库只保存模板。已配置 `paths` 过滤器，CI 在纯文档仓不会触发。

**契约定级规则**：若不同文档描述冲突，一律以 `DEV_SPECIFICATION.md` 第四章（接口契约）为准；其余以各自成员指南中"v1.1 审查回写"后的内容为准。发现新的冲突，停下问组长，不要自行发明。

## 二、组员操作指南（零编程经验版）

### 第 1 步：拿到自己的任务
确认你对应成员 A / B / C / D（见 `TEAM_WORK_DIVISION.md` 第一章表格）。

### 第 2 步：把文档喂给你的 AI Agent
> 看不懂文档里的术语？先读 `GLOSSARY.md`，里面有"人话版"术语表和报错速查表。

打开你的 AI 编程工具，在新项目的**第一条消息**里按此模板发送：

```text
请先完整阅读我提供的 3 份文档，严格遵照执行，不允许自由发挥：
1. AGENT_INSTRUCTIONS.md（全局禁令与配置模板，最高优先级）
2. DEV_SPECIFICATION.md（编码与接口规范）
3. MEMBER_<你的字母>_DEV_GUIDE.md（你负责模块的详细设计）

我现在的任务是：<从你成员指南"第X章"里抄一个具体任务>。
先列出你将创建/修改的文件清单让我确认，再开始写代码。
每次写完后，对照 AGENT_INSTRUCTIONS.md 第四章的 7 项冒烟清单自查并报告结果。
```

把三份文档的文件拖进对话（或在 Claude Code 里 `@文件名` 引用）。

### 第 3 步：小步快跑，每一步都要能编译
- 不要一次让 Agent "把整个后端写完"。按成员指南的章节拆小任务（如"只实现登录接口"）。
- 每完成一个小任务，让 Agent 运行 `mvn compile`（后端）或 `npm run build`（前端），**报错就继续让它修，不要跳过**。
- Agent 说"完成了"不等于完成——要求它贴出编译/运行成功的输出。

### 第 4 步：提交代码
按 `DEV_SPECIFICATION.md` 第一章执行：从 `dev` 分支拉 `feature/xxx` 分支，提交信息用 `feat(scope): 描述` 格式，发起 Pull Request 合并到 `dev`。**禁止把任何 API Key 写进代码或提交**（Key 放 `application-local.yml` 或环境变量，该文件已被 .gitignore 忽略）。

## 三、最重要的 6 条铁律（Agent 最容易违反的）

1. **只做 5 项核心功能，禁止自由发挥**：①课程资料构建 RAG 知识库、②学生提问智能答疑、③知识点解析、④问答记录、⑤教师后台管理（课件管理 + 问答记录查看），另加点赞/点踩与接口限流两项。
   **严禁自行添加**统计图表、数据导出、人工纠偏、知识点自测题、多轮对话、语音输入等功能。**少做一个功能，比多做一个功能更有价值**（详见 `THREE_WEEK_PLAN.md` 第一节的功能范围表）。
2. **单体 Spring Boot 工程**，严禁微服务/拆分多工程（包名 `com.smartqa.platform`）。
3. **SSE 协议只有 4 种事件**：`references` / `message` / `done` / `error`，全部 JSON 载荷，`done` 必含 `recordId`——格式以 `DEV_SPECIFICATION.md` 4.2 为唯一标准。
4. **所有 REST 接口返回 `Result<T>` 统一包装**，严禁裸返回。
5. **严禁硬编码密钥**；LLM Key、数据库密码一律环境变量注入。
6. **前后端字段不许猜**：接口先由成员 B 出 Knife4j 文档，前端照文档调用。Token 存 `localStorage` 统一键名 `satoken`，请求时**必须同时携带 `satoken` 与 `Authorization: Bearer <token>` 两个请求头**——只发后者会被判定未登录返回 401（见 `DEV_SPECIFICATION.md` 4.2）。

## 四、 当前项目阶段（3 周冲刺）

**工期为 3 周。每人每周的具体任务与验收标准见 `THREE_WEEK_PLAN.md`** —— 该文件是排期唯一权威（`TEAM_WORK_DIVISION.md` 的 5 周路线图已作废）。

开工第一周必须先做两个技术验证（成员 A 负责，各半天）：
1. **Chroma 元数据过滤验证**：确认 `langchain4j-chroma 0.35` 的 `EmbeddingSearchRequest.filter(IsEqualTo("courseId", ...))` 与 `removeAll(filter)` 真实可用。不可用则立即改用 LangChain4j 内置向量存储 + 本地文件持久化（成员 A 指南已允许此退路），并通知全员更新文档。
2. **大模型连通验证**：用 `AGENT_INSTRUCTIONS.md` 的 yml 模板直连通义千问/DeepSeek 兼容接口，跑通一次流式输出；同时验证种子账号 `teacher01/123456` 能 BCrypt 登录。

## 五、 开工前必做：第 0 周准备清单

在写第一行业务代码之前，**先把工程协作的地基打好**（约半天，组长牵头）：建代码仓、配分支保护、复制 CI 与 Issue/PR 模板、4 人环境自检、统一分发 Key、建看板。

完整 8 项清单见 `COLLABORATION_WORKFLOW.md` 附录 A。**跳过这一步，第 2 周必然踩坑**（合并冲突、密钥泄露、接口对不上）。

## 六、 许可证

本项目采用 [MIT License](./LICENSE)。
