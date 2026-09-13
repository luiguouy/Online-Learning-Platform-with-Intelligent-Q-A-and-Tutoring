# AI 驱动的在线学习智能答疑辅导平台（RAG 课程知识库）
## 团队全栈开发规范与工程协同守则 (v1.0)

> **适用团队**：4 人本科生课程设计 / 毕业设计研发小组  
> **适用技术栈**：Spring Boot 3.x + MyBatis-Plus + LangChain4j + MySQL + Sa-Token + Vue 3 + Vite + Element Plus  
> **制定目的**：统一代码风格、规范接口契约、杜绝“在自己电脑上能跑，合并就报错”、保障期末高质量高分交付。

---

## 一、 Git 协作与版本控制规范

### 1.1 分支管理策略 (轻量级 GitHub Flow)
团队采用单主干 + 特性分支模式，严禁直接向 `main` 分支推代码：

- **`main` 分支**：生产/演示发布分支。只有跑通所有测试、确认演示无 Bug 的版本才允许合并，此分支永远保持可运行状态。
- **`dev` 分支**：日常开发集成分支。所有成员完成各自模块后，发起 Pull Request / Merge Request 合并至 `dev`。
- **`feature/xxx` 分支**：个人特性开发分支。由每个成员从最新的 `dev` 分支拉出：
  - 成员 A（AI / RAG）：`feature/rag-engine`、`feature/sse-stream`
  - 成员 B（后端业务）：`feature/user-auth`、`feature/course-crud`
  - 成员 C（学生前端）：`feature/student-chat`、`feature/markdown-viewer`
  - 成员 D（教师前端）：`feature/teacher-admin`、`feature/qa-record-list`
- **`fix/xxx` 分支**：联调或测试期间的 Bug 修复分支。

### 1.2 Git 提交信息规范 (Conventional Commits)
每次提交（Commit）信息必须遵循标准格式：`<type>(<scope>): <subject>`

| Type 类型 | 含义 | 示例 |
| :--- | :--- | :--- |
| `feat` | 新增功能 | `feat(rag): 完成PDF文档切块与向量存储逻辑` |
| `fix` | 修复缺陷 | `fix(auth): 修复学生登录后Token过期未刷新问题` |
| `docs` | 仅文档修改 | `docs: 更新前后端接口契约文档` |
| `style` | 代码格式调整（不影响逻辑） | `style(chat): 优化问答气泡内边距与字体大小` |
| `refactor` | 代码重构 | `refactor(db): 重构问答记录表关联查询SQL` |
| `test` | 增加或修改测试用例 | `test(rag): 增加向量检索Top-K召回率单元测试` |

**禁止提交信息**：“update”、“fix bug”、“111”、“修改代码”等无意义内容。

### 1.3 敏感信息防泄露原则
- **绝对禁止将任何 API Key 上传至 Git 仓库**（如通义千问、DeepSeek、OpenAI 的 Key）。
- 后端根目录必须配置 `.gitignore`，忽略：
  - `target/`、`.idea/`、`*.iml`、`.vscode/`
  - `application-local.yml`（个人本地配置文件，包含密钥）
  - 上传的临时大课件文件（`uploads/*`）
- 提供一份脱敏的 `application-example.yml` 供组员克隆后重命名配置（模板见 `dev-docs/templates/application-example.yml`）。

> **配套流程**：分支保护规则的配置步骤、PR/Code Review 检查清单、CI 门禁，见 `dev-docs/COLLABORATION_WORKFLOW.md` 第二至四节；"什么算完成"见其第五节 DoD。本文件只规定规范，流程以协作文档为准。

---

## 二、 后端 Java / Spring Boot 编码与分层规范

### 2.1 架构分层职责划分
后端严格遵守标准 MVC 四层分层，杜绝在 Controller 中写业务逻辑，杜绝在 Service 中直接拼装 HttpServletResponse：

```text
com.smartqa.platform
├── common/              // 通用基础包 (Result封装, 异常枚举, 常量)
├── config/              // 配置类 (Sa-Token, MyBatis-Plus, 跨域CORS, 向量库Bean)
├── controller/          // 控制层 (仅负责入参校验、路由分发、返回统一Result/SSE)
├── service/             // 业务逻辑层 (业务校验、事务管理、组合调用)
│   └── impl/
├── rag/                 // AI/RAG核心包 (分块切片, Prompt模板, 向量检索)
├── dao/                 // 数据持久层 (Mapper接口与XML文件)
└── model/               // 数据模型
    ├── entity/          // 数据库实体类 (与表结构1:1严格映射)
    ├── dto/             // 前端传入的数据传输对象 (如 LoginDTO, UploadDocDTO)
    └── vo/              // 返回给前端的视图对象 (如 ChatMessageVO, CourseDetailVO)
```

### 2.2 命名规范
- **类名**：大驼峰命名（UpperCamelCase），如 `CourseDocumentController`、`RagSearchService`。
- **方法名/变量名**：小驼峰命名（lowerCamelCase），如 `queryChunksByCourseId`、`sessionId`。
- **常量名**：全大写加下划线，如 `DEFAULT_CHUNK_SIZE`、`TOKEN_HEADER_NAME`。
- **实体类禁止加前缀**：禁止写 `TCourse` 或 `T_USER`，统一使用 `Course`、`SysUser`。
- **Service 与实现类**：接口命名为 `CourseService`，实现类命名为 `CourseServiceImpl`。

### 2.3 异常处理与统一响应规范
所有对外提供的 REST 接口必须返回统一包装类 `Result<T>`，严禁各写各的格式：

```java
// 通用响应结构封装
public class Result<T> implements Serializable {
    private Integer code;    // 200 成功，400 业务警告，401 未登录，403 无权，500 系统异常
    private String message;  // 提示信息
    private T data;          // 数据载荷
    private Long timestamp;  // 时间戳
}
```

- **全局异常拦截**：使用 `@RestControllerAdvice` 统一捕获 `BusinessException`、参数校验异常（`MethodArgumentNotValidException`）及未处理运行时异常，返回友好的错误 JSON，**严禁将 Java 堆栈错误抛给前端界面**。
- **参数校验**：Controller 层入参必须使用 Spring Validation 注解（`@NotBlank`, `@NotNull`, `@Size`），并在 DTO 上添加 `@Valid`。

### 2.4 数据库操作与 SQL 规范
- **ID 生成策略**：统一使用雪花算法（Snowflake / `IdType.ASSIGN_ID`）或 MySQL 自增主键（统一团队选型后保持一致）。
- **软删除与审计字段**：重要业务表必须包含：
  - `created_at` (DATETIME, 默认 CURRENT_TIMESTAMP)
  - `updated_at` (DATETIME, 默认 CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)
- **避免 SELECT \***：在自定义 XML 中，严禁使用 `SELECT *`，必须显式声明所需的列名，避免课件大文本字段（如切块大文本）被无意义扫描拉慢性能。

---

## 三、 前端 Vue 3 + TypeScript 编码规范

### 3.1 目录结构标准
```text
src/
├── api/             // 所有后端接口请求函数 (按模块拆分: auth.ts, course.ts, qa.ts)
├── assets/          // 静态资源 (图片, 字体, 全局CSS)
├── components/      // 通用高复用UI组件 (MarkdownViewer, GroundingDrawer, ChatBubble)
├── router/          // 路由配置与全局路由守卫 (未登录拦截)
├── stores/          // Pinia 状态管理 (userStore, courseStore, chatStore)
├── types/           // 全局 TypeScript 类型声明
├── utils/           // 工具函数 (SSE请求封装, 日期格式化, 复制文本)
└── views/           // 页面组件
    ├── auth/        // 登录页（本期不实现注册）
    ├── student/     // 学生前台问答工作台、知识点解析展示
    └── teacher/     // 教师后台课件管理、问答审计页
```

### 3.2 组件开发与编写规范
- **统一使用 `<script setup lang="ts">`**：严禁混用 Vue 2 Options API 与 Vue 3 Composition API。
- **组件命名规范**：
  - 页面级组件采用大驼峰：`StudentChatView.vue`、`TeacherDocManage.vue`。
  - 基础子组件必须使用多词大驼峰：`ChatInputBox.vue`、`SourceCard.vue`。
- **Props 与 Emits 必须显式声明类型**：
  ```vue
  <script setup lang="ts">
  interface Props {
    sessionId: number;
    courseName?: string;
    loading: boolean;
  }
  const props = withDefaults(defineProps<Props>(), {
    courseName: '通用课程',
    loading: false
  });
  const emit = defineEmits<{
    (e: 'sendQuestion', text: string): void;
  }>();
  </script>
  ```
- **样式规范**：
  - 统一使用 `<style scoped>` 或 Tailwind CSS 工具类，严禁污染全局全局样式选择器。
  - Element Plus 表格、按钮的色彩需统一遵循设计主题（主色采用 Indigo `#4F46E5`）。

### 3.3 SSE 流式通信与 Markdown 渲染安全
- **流式请求管理**：推荐使用 `@microsoft/fetch-event-source` 库，在用户切换会话或离开页面时，**必须调用 `abort()` 中断未完成的 SSE 流**，防止内存泄漏和后端无意义调用。
- **XSS 防范**：渲染大模型返回的 Markdown 内容时，必须启用代码转义或使用 `DOMPurify` 进行白名单过滤，严禁直接 `v-html` 未经清洗的外部文本。

---

## 四、 RESTful API 与接口协同契约

### 4.1 URL 路由设计守则
- 资源使用名词复数，小写字母加短横线（kebab-case）：
  - 正确：`/api/teacher/course-documents`
  - 错误：`/api/teacher/getCourseDocList`、`/api/teacher/update_doc`
  > ⚠️ 本节只是命名**原则**，不是接口清单。4.2 已冻结的实际契约路径（如 `/api/teacher/docs/upload`、`/api/teacher/docs/list`）与本页示例不一致时，**一律以 4.2 为准**，严禁按示例"顺手改名"——改名就是契约变更，必须走 `COLLABORATION_WORKFLOW.md` 6.3 四步法。
- HTTP 动词语义化：
  - `GET`：查询数据（幂等，不得产生业务副作用）
  - `POST`：创建资源或执行复杂查询
  - `PUT / PATCH`：更新修改资源
  - `DELETE`：删除资源

### 4.2 SSE (Server-Sent Events) 核心协议标准
智能答疑流式接口路径固定为：`GET /api/qa/chat/stream?courseId={id}&sessionId={id}&question={text}`

> **GET 副作用例外说明**：本接口虽为 GET，但会产生业务写入（懒创建会话、落库问答记录），属于本规范 4.1 条的显式例外，原因是保持浏览器原生 SSE 兼容。前端必须使用 `@microsoft/fetch-event-source` 发起（以便携带 Authorization 请求头）。

响应头必须包含：
```http
Content-Type: text/event-stream; charset=UTF-8
Cache-Control: no-cache
Connection: keep-alive
```

流式数据包事件流定义（严格遵循 4 种 Event，**所有 data 载荷一律为 JSON**，防止 token 中含换行符破坏 SSE 帧格式）：
1. **`event: references`**（首包下发，检索到的知识库出处，字段名与后端 `SseReferenceVO` 严格一致）：
   ```json
   data: [{"docId":12,"fileName":"第3章 内存管理.pdf","chunkIndex":14,"score":0.88,"snippet":"虚拟内存分页机制中，页表存储了虚页号与物理页框号的映射关系..."}]
   ```
2. **`event: message`**（增量生成，流式吐字）：
   ```json
   data: {"delta": "在操作系统中，"}
   data: {"delta": "分页式存储管理是将虚拟地址空间..."}
   ```
3. **`event: done`**（结束标记包，**必须携带 recordId**，供前端点赞/点踩与问答记录查询使用）：
   ```json
   data: {"recordId": 1024, "sessionId": 7, "finishReason": "stop", "totalTokens": 328}
   ```
4. **`event: error`**（异常中断包）：
   ```json
   data: {"errorCode": 5001, "message": "大模型调用超时，请重试"}
   ```

**会话懒创建规则**：前端进入页面时不预建会话；`sessionId` 传 `0` 或省略时，后端自动在 `qa_session` 插入新会话（标题取问题前 15 个字符），并在 `done` 包中回传真实 `sessionId` 与 `recordId`。

**鉴权请求头统一约定（v6.0 锁定，全团队唯一标准）**：登录返回的 Token 以键名 `satoken` 存入 `localStorage`（**它只是本地存储的名字，与请求头名无关**）。
所有请求（**含 SSE**）只需携带**一个**请求头：

```http
Authorization: Bearer <token>
```

依据：`sa-token.token-name` 配为 `Authorization`、`token-prefix` 配为 `Bearer`，因此 Sa-Token 读取该请求头并自动剥离 `Bearer ` 前缀。
⚠️ **头值必须带 `Bearer ` 前缀（含一个空格）**。只写裸 token 会被判定未登录，返回 **401**。不要再额外发送 `satoken` 请求头——那是多余的。

**课件解析状态机统一约定**：`PENDING(排队中) -> PARSING(切块中) -> CHUNKED(已就绪) -> FAILED(失败)`，数据库与前端标签均使用此四态，禁止使用 `PROCESSING`/`SUCCESS` 等别名。

> **`PENDING` 的实际语义**：本项目单机同步提交切块任务，课件落库后**直接进入 `PARSING`**（见 `MEMBER_B_DEV_GUIDE.md` 4.3 上传代码，注释与状态值已对齐）。`PENDING` 保留为"已排队、尚未提交切块"的中间态；前端状态标签仍需能渲染它，但正常流程下一般观察不到——**不要因为"看不到 PENDING"就以为状态机写错了**。

**向量库 Collection 统一约定**：`smart_qa_course_docs`，禁止各文档各起别名。

---

## 五、 AI 与 RAG 专项工程规范 (成员 A 核心把关)

### 5.1 文档切块（Chunking）规范
- **切分大小**：固定为 `chunk_size = 400` 字符，重叠窗口 `chunk_overlap = 50` 字符。
- **断句优先**：必须采用递归字符切分器（RecursiveCharacterSplitter），优先以 `\n\n`（段落）、`\n`（换行）、句号、问号作为自然切分断点，**严禁从英文单词中间或公式符号中间切断**。
- **元数据必填项**：切出的每一个分块必须注入以下元数据：
  - `courseId`：课程 ID（用于检索隔离）
  - `docId`：课件文档 ID
  - `fileName`：课件原始文件名
  - `chunkIndex`：分块在其文档中的自增序号

### 5.2 向量检索与防幻觉 Prompt 规范
- **检索阈值**：Top-K 取值推荐 `K = 3 ~ 4`，设置相似度最低阈值（如 Cosine 相似度 ≥ 0.70），低于阈值的片段抛弃，避免强行拼接无关课件内容。
- **系统提示词 (System Prompt) 严谨性模板**：
  ```text
  你是一名经验丰富的计算机学科助教。请严格基于【参考资料】中列出的内容，解答学生的【提问】。
  
  【参考资料】：
  {{context}}
  
  【回答纪律】：
  1. 答案必须严格基于【参考资料】作答，绝不可捏造或发散推测。
  2. 若【参考资料】中未提及相关信息，请明确回答：“在当前课程课件中未找到该问题的明确说明”；若提供学科常识性延伸，必须显式标注“【课外补充说明】”，不得与课件内容混淆。
  3. 使用规范 Markdown 语法组织回答，包含概念阐述、分析步骤和代码示例（如有）。
  ```
- **模型参数调优**：
  - 智能答疑场景：设置 `temperature = 0.2`，降低发散性，确保回答严谨基于课件。
  - 知识点精解场景：设置 `temperature = 0.6`，让讲解表述更自然。

---

## 六、 环境配置与日常联调纪律

### 6.1 本地开发环境统一
为避免环境版本导致的玄学 Bug，团队环境基线统一定义如下：
- **JDK**：OpenJDK / Oracle JDK 17 (LTS)
- **Spring Boot**：**锁定 `3.3.5`**（`spring-boot-starter-parent` 版本，v7.0 锁定——其余依赖均为 2024 上半年发布，在 3.2/3.3 时代验证过；用 Initializr 建工程后必须把版本改回 3.3.5）
- **构建工具**：Maven 3.8+
- **Node.js**：Node.js 18.x 或 20.x (LTS) + pnpm / npm 9+
- **MySQL**：MySQL 8.0+（编码务必设为 `utf8mb4`）
- **向量库**：Chroma **锁定 `0.5.23`**（Docker: `chromadb/chroma:0.5.23`）
  - ⚠️ **严禁用 `latest`**：`langchain4j-chroma:0.35.0` 的客户端**只支持 Chroma API V1**；Chroma 服务端 **0.7.0 起只保留 API V2**（2025-04 发布的 1.0 更是纯 V2）——`latest` 必然连不上。0.5.23 是最后一个 0.5.x，V1 完整可用（v7.0 查证 LangChain4j 官方文档与 Chroma release 记录）。
  - 启动命令：`docker run -d --name chroma -p 8000:8000 -v chroma-data:/chroma chromadb/chroma:0.5.23`
  - 验证：`curl http://localhost:8000/api/v1/heartbeat` 返回 `{"nanosecond heartbeat": ...}` 即正常。

### 6.2 联调与代码合并纪律
1. **先拉取后提交**：每天开始写代码前，先 `git pull origin dev`；提交 PR 前，先在本地 rebase 或 merge 最新的 `dev` 并编译通过。
2. **前后端接口先行**：成员 B 必须先输出 Knife4j 接口定义或 Postman Mock 集合，成员 C/D 方可开始调用，严禁“前端盲猜后端字段”。
3. **冒烟测试再合并**：日常 PR 的合并条件以 `COLLABORATION_WORKFLOW.md` 3.4 三条为准（CI 全绿 + 1 人 Approve + 评论清完）。组长的端到端冒烟（登录 -> 课件上传 -> 提问输出打字机效果 -> 记录可查）是 **Gate 日（每周日集成评审）的义务**，不是每个 PR 的前置条件——否则组长会变成全队的瓶颈。
4. **每周同步看 Gate**：周一不再有单独的站会——每日站会已迁到任务 Issue 评论区（`COLLABORATION_WORKFLOW.md` 8.2），每周节奏以周日 Gate 评审 + 周三中期集成为准（`THREE_WEEK_PLAN.md` 第六节）。
