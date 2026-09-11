export interface MemberInfo {
  id: string;
  role: string;
  name: string;
  tag: string;
  focus: string;
  deliverables: string[];
  tasks: string[];
}

export const defaultMembers: MemberInfo[] = [
  {
    id: 'A',
    role: '成员 A（组长 / AI 与 RAG 核心架构师）',
    name: '成员 A',
    tag: 'RAG & AI 算法链路',
    focus: '负责整体技术把关、大模型与向量链路闭环、Prompt 工程',
    deliverables: [
      'Spring Boot + LangChain4j 核心工程骨架',
      '课件文档切块与向量索引流水线',
      'Server-Sent Events (SSE) 流式答疑接口',
      '知识点考点生成与 Prompt 优化模板',
    ],
    tasks: [
      '技术选型与 Git 团队代码仓库规范制定',
      '基于 LangChain4j 实现 PDF/Markdown/TXT 文档智能切块 (Chunk: 400, Overlap: 50)',
      '对接 Embedding 模型与向量数据库 (Chroma / InMemory)',
      '编写针对特定 courseId 的 Top-K 向量检索与上下文组装',
      '编写 SSE 流式控制器 /api/qa/chat/stream（多事件：references, message, done）',
      '设计知识点深度解析与 3 道自测选择题生成 Prompt',
    ],
  },
  {
    id: 'B',
    role: '成员 B（后端业务与数据库工程师）',
    name: '成员 B',
    tag: '数据模型与业务中台',
    focus: '负责业务实体设计、MySQL 数据持久化、用户认证及接口鉴权',
    deliverables: [
      'MySQL 数据库 DDL 建表脚本与初始化数据',
      'MyBatis-Plus 业务层 CRUD 代码',
      'Sa-Token 权限鉴权与 JWT 拦截器',
      'Knife4j / Swagger 接口规范文档',
    ],
    tasks: [
      '设计系统核心表：sys_user, course, course_document, qa_session, qa_record 等',
      '集成 Sa-Token 框架，实现学生与教师的角色权限鉴权',
      '开发课程管理、课件上传存储（本地/MinIO）及状态轮询接口',
      '实现问答记录异步持久化与学生点赞/点踩反馈 API',
      '编写教师后台数据统计分析接口（提问走势、课程活跃度）',
      '使用 Knife4j 统一输出后端 RESTful 接口文档',
    ],
  },
  {
    id: 'C',
    role: '成员 C（前端开发工程师 - 学生交互核心）',
    name: '成员 C',
    tag: '学生端体验与流式动效',
    focus: '负责学生前台界面、流式打字机视觉呈现、Markdown 解析与出处展开',
    deliverables: [
      'Vue 3 + Vite + Element Plus 学生端界面',
      'EventSource / SSE 打字机流式组件',
      'Markdown + 代码高亮 + KaTeX 公式渲染器',
      '知识溯源抽屉 (Grounding Drawer) 组件',
    ],
    tasks: [
      '搭建 Vue 3 前端工程脚手架，配置 Pinia 状态管理与路由守卫',
      '对接后端 SSE 接口，实现平滑打字机吐字与光标闪烁动效',
      '集成 markdown-it / v-md-editor 与 highlight.js 实现代码高亮与一键复制',
      '开发右侧“知识溯源看板”，展示命中课件名、页码、段落及相似度分值',
      '开发历史问答会话列表、新建对话及会话检索功能',
      '开发知识点精解卡片与交互式自测题答题组件',
    ],
  },
  {
    id: 'D',
    role: '成员 D（前端开发与质检工程师 - 教师后台与文档）',
    name: '成员 D',
    tag: '教师后台与项目验收',
    focus: '负责教师后台管理系统、学情看板、前后端联调测试及答辩验收文档',
    deliverables: [
      '教师管理后台完整页面模块',
      'ECharts 学情统计与高频问答图表',
      '前后端联调测试用例与 Bug 追踪表',
      '软件工程规格说明书与答辩汇报 PPT',
    ],
    tasks: [
      '开发教师后台界面：课程列表、课程创建与编辑表单',
      '开发课件知识库管理界面：拖拽批量上传、解析进度条与重做索引按钮',
      '开发学生提问审计页面，实现“教师人工修正/纠偏”提交功能',
      '集成 ECharts 绘制学生提问活跃度折线图与课程疑问分布图',
      '准备标准测试用教材课件集（操作系统/计算机网络等 3~5 篇典型讲义）',
      '执行端到端黑盒测试，统筹编写项目结题报告与答辩演示 PPT',
    ],
  },
];

export function generateMarkdownDoc(members: MemberInfo[]): string {
  const mA = members.find((m) => m.id === 'A') || defaultMembers[0];
  const mB = members.find((m) => m.id === 'B') || defaultMembers[1];
  const mC = members.find((m) => m.id === 'C') || defaultMembers[2];
  const mD = members.find((m) => m.id === 'D') || defaultMembers[3];

  return `# AI驱动的在线学习智能答疑辅导平台（RAG课程知识库）
## 4人小组详细分工与项目协同实施方案

> **项目名称**：AI 驱动的在线学习智能答疑辅导平台（RAG 课程知识库）  
> **团队规模**：4 人（本科生软件工程 / 计算机专业课程设计团队）  
> **团队成员**：${mA.name}（组长/AI架构）、${mB.name}（后端业务）、${mC.name}（学生端前端）、${mD.name}（教师端前端与文档）  
> **设计基调**：轻量实用、高内聚低耦合、避免过度工程化、保障演示闭环  
> **核心交付**：Java 全栈架构、RAG 课件知识库检索、流式打字机答疑、知识点深度解析、问答记录持久化、教师后台管理

---

## 一、 团队角色定位与职责总览

为确保任务界限清晰、各司其职且互不阻塞，团队采用 **“2 后端 + 2 前端”** 架构矩阵：

| 角色编号 | 责任人 | 核心职责 | 主负责模块 | 核心交付物 |
| :--- | :--- | :--- | :--- | :--- |
| **成员 A** | **${mA.name}**<br>*(组长 / AI与RAG核心)* | 负责整体技术把关、大模型与向量链路闭环 | RAG 知识库构建流水线、向量检索、SSE 流式接口、知识点生成 Prompt | 1. RAG 核心服务与切块模块<br>2. 大模型流式输出 (SSE) 接口<br>3. 系统架构设计图与技术选型 |
| **成员 B** | **${mB.name}**<br>*(后端业务与数据库)* | 负责业务实体设计、数据持久化及接口鉴权 | 用户与权限管理、课程与课件元数据 CRUD、问答历史存储、教师管理 API | 1. MySQL 初始化 DDL 脚本<br>2. MyBatis-Plus 业务接口代码<br>3. Knife4j / Swagger 接口文档 |
| **成员 C** | **${mC.name}**<br>*(前端开发/学生交互)* | 负责学生前台界面与极致交互体验 | 学生端主页、实时流式问答界面、Markdown/代码高亮渲染、参考出处抽屉 | 1. 学生端完整交互界面<br>2. SSE 打字机流式组件<br>3. Markdown 与数学公式渲染器 |
| **成员 D** | **${mD.name}**<br>*(前端开发/教师后台与质检)* | 负责教师后台系统、集成测试及项目汇报验收 | 课件上传管理界面、问答记录审查与纠偏、学情可视化图表、文档报告与 PPT | 1. 教师端后台管理界面<br>2. 统计图表看板 (ECharts)<br>3. 软件工程全套文档与答辩 PPT |

---

## 二、 成员细粒度分工计划清单 (WBS)

### 2.1 成员 A：${mA.name}（组长 / AI 与 RAG 核心架构师）
- **核心定位**：全链路 AI 引擎与数据流驱动者
- **具体工作清单**：
${mA.tasks.map((t, idx) => `  ${idx + 1}. **${t.split('：')[0]}**：${t}`).join('\n')}
- **关键交付成果**：
${mA.deliverables.map((d) => `  - [ ] ${d}`).join('\n')}

---

### 2.2 成员 B：${mB.name}（后端业务与数据库工程师）
- **核心定位**：数据中台与业务流程支撑者
- **具体工作清单**：
${mB.tasks.map((t, idx) => `  ${idx + 1}. **${t.split('：')[0]}**：${t}`).join('\n')}
- **关键交付成果**：
${mB.deliverables.map((d) => `  - [ ] ${d}`).join('\n')}

---

### 2.3 成员 C：${mC.name}（前端开发工程师 - 学生交互核心）
- **核心定位**：前台答疑体验与流式呈现实现者
- **具体工作清单**：
${mC.tasks.map((t, idx) => `  ${idx + 1}. **${t.split('：')[0]}**：${t}`).join('\n')}
- **关键交付成果**：
${mC.deliverables.map((d) => `  - [ ] ${d}`).join('\n')}

---

### 2.4 成员 D：${mD.name}（前端开发与质检工程师 - 教师后台与文档）
- **核心定位**：教师运营后台搭建、全流程把关与答辩负责人
- **具体工作清单**：
${mD.tasks.map((t, idx) => `  ${idx + 1}. **${t.split('：')[0]}**：${t}`).join('\n')}
- **关键交付成果**：
${mD.deliverables.map((d) => `  - [ ] ${d}`).join('\n')}

---

## 三、 前后端与 AI 协同接口矩阵 (API Matrix)

| 接口功能 | 请求路径 | 方式 | 提供方 (责任人) | 调用方 (责任人) | 数据说明 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **用户登录** | \`/api/auth/login\` | \`POST\` | ${mB.name} | ${mC.name} & ${mD.name} | 账号密码 -> Token & Role 菜单 |
| **课程列表** | \`/api/course/list\` | \`GET\` | ${mB.name} | ${mC.name} & ${mD.name} | 课程列表与状态 |
| **课件上传** | \`/api/teacher/docs/upload\` | \`POST (Form)\` | ${mB.name} & ${mA.name} | ${mD.name} | 上传文件并触发异步切块分片 |
| **重新索引** | \`/api/teacher/docs/{id}/reindex\` | \`POST\` | ${mA.name} | ${mD.name} | 重建向量索引 |
| **智能答疑 (SSE)** | \`/api/qa/chat/stream\` | \`GET (SSE)\` | ${mA.name} | ${mC.name} | \`question, courseId\` -> 流式增量吐字与出处 |
| **历史会话** | \`/api/qa/sessions\` | \`GET\` | ${mB.name} | ${mC.name} | 历史会话列表与对话记录 |
| **问答点赞** | \`/api/qa/records/{id}/feedback\` | \`POST\` | ${mB.name} | ${mC.name} | \`recordId, status(1/-1)\` |
| **知识点精解** | \`/api/knowledge/generate\` | \`POST\` | ${mA.name} | ${mC.name} | 知识点 -> 结构化考点与自测题 |
| **教师纠偏** | \`/api/teacher/qa/correct\` | \`POST\` | ${mB.name} | ${mD.name} | 教师覆盖 AI 不准确答疑 |

---

## 四、 5 周推进路线图与关键里程碑 (Timeline)

\`\`\`text
第 1 周 [基础对齐] ──> 第 2 周 [骨架与CRUD] ──> 第 3 周 [RAG核心攻坚] ──> 第 4 周 [前后端全量闭环] ──> 第 5 周 [压测答辩]
(4人统一接口规范)     (数据库建表与原型搭建)   (SSE流式打字机跑通)      (教师后台与知识点解析)       (PPT制作与模拟演练)
\`\`\`

- **第 1 周：技术准备与方案定型**
  - 【全体】明确演示课程（如《计算机操作系统》），准备测试 PDF 课件。
  - 【${mA.name}】跑通大模型 API 与本地 Embedding 最小 Demo。
  - 【${mB.name}】完成 MySQL 建表并导出 SQL 脚本。
  - 【${mC.name} & ${mD.name}】确定原型界面交互逻辑。
- **第 2 周：骨架搭建与基础接口联调**
  - 【${mA.name}】实现文档解析引擎与分块切片算法。
  - 【${mB.name}】搭建 Spring Boot 基础工程，完成登录与基础 CRUD。
  - 【${mC.name}】搭建 Vue3 基础工程，实现学生端框架与侧边栏。
  - 【${mD.name}】搭建教师后台管理框架，实现课程管理页面。
- **第 3 周：RAG 检索与智能答疑闭环（核心攻坚周）**
  - 【${mA.name}】完成向量检索与 Prompt 注入，暴露 SSE 流式接口。
  - 【${mB.name}】实现课件异步上传状态机与问答明细存储。
  - 【${mC.name}】对接 SSE 接口，实现打字机流式输出与参考资料展开。
  - 【${mD.name}】实现课件拖拽上传与解析进度动态展示。
- **第 4 周：知识点解析、教师后台与交互完善**
  - 【${mA.name}】开发基于知识库的考点精解与自测题生成接口。
  - 【${mB.name}】实现数据统计接口与教师纠偏保存逻辑。
  - 【${mC.name}】完成知识点卡片视图、答疑反馈与历史会话切换。
  - 【${mD.name}】完成教师问答审查表格与 ECharts 学情可视化看板。
- **第 5 周：端到端验收、文档封板与答辩准备**
  - 【全体】进行全链路功能集成联调与边界异常测试（防大模型超时、防并发卡顿）。
  - 【${mC.name} & ${mD.name}】UI 细节优化，添加加载占位骨架屏与空状态提示。
  - 【${mD.name} 牵头】完成系统设计说明书、使用手册录制及答辩演示 PPT。

---

## 五、 本科生答辩核心得分亮点

1. **真实可信的 RAG 溯源展示**：每条 AI 回答皆可展开命中课件页码及段落摘要，避免被评委质疑为“单纯套壳大模型”。
2. **极佳的答辩现场表现力**：支持流式打字机动效、代码高亮与公式渲染，告别白屏傻等。
3. **教师人工纠偏闭环**：当大模型出现幻觉时，教师可实时更正答案并沉淀为标准问答对，突出“工程可控性”。
4. **分工均衡且边界清晰**：2 后端 + 2 前端，代码仓提交规范，文档齐全，契合工程规范评价标准。
`;
}
