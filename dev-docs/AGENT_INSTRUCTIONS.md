# AI 驱动的在线学习智能答疑辅导平台（RAG 课程知识库）
## AI Agent 执行指令清单与防坑实战手册 (AGENT_INSTRUCTIONS.md)

> **文档性质**：面向自动化编程 Agent（如 Cursor, Windsurf, Claude Code, GitHub Copilot Workspace, Qwen-Code 等）的系统提示与工程约束指南。  
> **核心目标**：消除 Agent 编码时的“自由发挥与过度设计”，确保一次性生成可用、可编译、可联调的生产级 Java + Vue3 代码。

---

## 零、 AI Agent 核心原则与禁令（System Constraints）

1. **严格单一工程，禁止拆分微服务**：
   - 严禁引入 Spring Cloud、Nacos、Eureka、Feign、Dubbo、Seata。
   - 必须采用**单体 Spring Boot 3.x** 应用，包名统一为 `com.smartqa.platform`。
   - **Spring Boot 版本锁定为 `3.3.5`**（`spring-boot-starter-parent`），JDK 锁定 17。其余依赖均发布于 2024 上半年，均在 3.2/3.3 时代验证过；若用 Initializr 建工程，**必须把版本改回 3.3.5**，禁止用它给的最新版（v7.0 锁定）。
2. **禁止擅自新增未约定的第三方依赖**：
   - 仅允许使用 `pom.xml` 与 `package.json` 中明确列出的库。严禁引入无意义的通用工具库或废弃库。
3. **接口返回格式红线**：
   - 普通 RESTful 接口必须严格返回 `com.smartqa.platform.common.Result<T>`。
   - 智能答疑 SSE 接口必须严格输出 `text/event-stream`，且只能包含 `references`、`message`、`done`、`error` 4 种事件类型。
4. **禁止空桩代码（No Mock Stubs）**：
   - 严禁在 Service 中写 `return null;` 或 `// TODO: implement later`。
   - 必须实现完整的数据库查询、异常抛出与逻辑校验。
   - **唯一例外**：`THREE_WEEK_PLAN.md` 任务 **B1.5**——成员 B 在 Day 3 前交付给 A 的 `saveStreamingRecord` / `createSessionLazy` 两个方法，允许内部先返回假数据，但**方法签名与返回类型必须冻结**且必须打 `// B1.5 临时桩：A2.3 联调前由 B 替换为真实实现` 注释标明替换点。除此之外一律适用本禁令。
5. **绝对禁止在代码和公共配置中硬编码真实生产密钥**：
   - 严禁将任何真实的大模型 API Key 或生产数据库密码提交至 Git 仓库。
   - 本地开发统一通过 `application-local.yml`（已加入 `.gitignore`）或环境变量（`${AI_API_KEY}`、`${MYSQL_PASSWORD}`）注入。公共 `application.yml` 中的占位默认值仅供本地离线沙箱开箱即用。
6. **文档中的命令行必须「可执行或可替代」（2026-10-04 由 Issue #64 确立；③ 档同日由 A 补）**：
   - 项目文档（`README.md`、`dev-docs/**`、各成员指南）里出现的**任何命令行**，必须满足三者之一：
     ① **仓库内可直接执行** —— 涉及的脚本与路径均在版本控制内；
     ② **显式标注「本地产物 / 未入库」**，**并同时给出他人可用的等价路径**；
     ③ **命令在部分环境不可执行时，同样要给出该环境的等价写法** —— 典型两类：POSIX shell 语法（`export` / `unset` / `<` 输入重定向）在 Windows PowerShell 下直接报错；npm script 的 shim 在 pwsh 下把模块路径解析到 workspace 父目录。
   - ❌ 反例（①② 档，本规则立项前实际出现，已修）：`bash 项目/D/backend-start/start-backend.sh` —— `项目/D/` 是成员 D 的本地目录，不在仓库内，他人照做必然失败。
   - ❌ 反例（③ 档，**本文自己曾犯**，已修）：§5 冒烟清单第 2 项原本只写 `npm run build`，而它在 Windows PowerShell 下会报 `Cannot find module '...\vue-tsc\bin\vue-tsc.js'`（`MODULE_NOT_FOUND`）—— 与代码无关，CI 在 Ubuntu 上正常，所以很容易长期没人发现。**规则立项当天就由它自己的正文破了例**，故此处一并补上等价写法。
   - ✅ 正例：`backend/run-local.ps1`（仓库内）；或写「本机脚本（未入库）；他人等价：在 `backend/` 执行 `.\run-local.ps1`」；③ 档如「`export X=…`（POSIX）／PowerShell 等价 `$env:X='…'`」。
   - 参照 `dev-docs/B3-第三周交付与验收报告.md` §四.1 对 probe 脚本的处理方式。

---

## 一、 后端工程实现（面向后端 Agent 任务）

### 1.1 项目结构与包定义规范
```text
com.smartqa.platform
├── common/
│   ├── Result.java                  // 统一响应包装
│   ├── BusinessException.java       // 自定义业务异常
│   ├── GlobalExceptionHandler.java  // @RestControllerAdvice 全局异常拦截
│   └── BaseEntity.java              // id, createdAt, updatedAt
├── config/
│   ├── MyBatisPlusConfig.java       // 分页插件与审计注入
│   ├── SaTokenConfigure.java        // Sa-Token 路由拦截器与权限配置
│   ├── CorsConfig.java              // WebMvc 跨域配置
│   ├── AsyncThreadPoolConfig.java   // 异步与SSE线程池配置
│   └── LangChain4jConfig.java       // LLM、EmbeddingModel、EmbeddingStore Bean
├── controller/
│   ├── AuthController.java              // /api/auth/*
│   ├── CourseController.java            // /api/course/list
│   ├── SseChatController.java           // /api/qa/chat/stream (SSE)
│   ├── QaSessionController.java         // /api/qa/sessions、/api/qa/records、feedback
│   ├── KnowledgeController.java         // /api/knowledge/generate
│   ├── TeacherDocumentController.java   // /api/teacher/docs/*
│   └── TeacherQaController.java         // /api/teacher/qa/records
├── service/
│   ├── SysUserService.java
│   ├── CourseService.java
│   ├── CourseDocumentService.java        // 含 updateParseStatus(docId, status, chunkCount)
│   ├── QaSessionService.java             // 含 createSessionLazy(courseId, question)
│   ├── QaRecordService.java              // 含 saveStreamingRecord(...) 与 pageRecords(...)
│   └── rag/
│       ├── DocumentIngestionService.java // 文档切片与入库（含 removeDocumentVectors）
│       ├── RagRetrievalService.java      // 向量召回与上下文组装（单路向量检索，无纠偏库）
│       └── SseStreamService.java         // SSE 流式推送（4 事件 + 落库回写 recordId）
├── dao/                             // MyBatis-Plus Mapper 接口与 XML
└── model/
    ├── entity/                      // 数据库表 1:1 映射
    ├── dto/                         // 入参对象 (@Valid 校验)
    └── vo/                          // 返回给前端的视图对象
```

### 1.2 必须配置的 application.yml 完整模板
```yaml
server:
  port: 8080
  servlet:
    context-path: /
    encoding:
      charset: UTF-8
      force: true

spring:
  application:
    name: smart-qa-platform
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/${MYSQL_DB:smart_qa}?useUnicode=true&characterEncoding=utf8mb4&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASSWORD:123456}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000

  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 60MB

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      id-type: auto
      logic-delete-field: isDeleted
      logic-delete-value: 1
      logic-not-delete-value: 0

sa-token:
  token-name: Authorization       # ★ 关键：Sa-Token 读取的请求头名就是它
  timeout: 604800 # 7天免登录
  active-timeout: 86400
  is-concurrent: true
  is-share: true
  token-style: uuid
  is-read-header: true
  is-read-cookie: false
  token-prefix: Bearer            # ★ 关键：头值须带 "Bearer " 前缀（含一个空格）

# ============================================================================
# ⚠️ 鉴权请求头铁律（v6.0 锁定，全团队唯一标准）
# ----------------------------------------------------------------------------
# token-name 设为 Authorization，所以 Sa-Token 读的是 Authorization 请求头；
# 又配了 token-prefix，因此头值必须是「Bearer + 空格 + token」：
#
#     Authorization: Bearer <token>
#
# 前端只需要发这一个头（不要再另发 satoken 头，那是多余的）。
# 前端 localStorage 的键名仍沿用 satoken —— 它只是本地存储的名字，与请求头名无关。
# 本条对 SSE（/api/qa/chat/stream）同样适用，详见 2.1 请求层与 C 指南 4.1。
# ============================================================================

rag:
  llm:
    # 聊天大模型端点：可直连 DeepSeek 官方 (https://api.deepseek.com/v1) 或 阿里云百炼 (https://dashscope.aliyuncs.com/compatible-mode/v1)
    base-url: ${AI_BASE_URL:https://api.deepseek.com/v1}
    api-key: ${AI_API_KEY:sk-placeholder}
    chat-model: ${AI_CHAT_MODEL:deepseek-chat}    # 支持 deepseek-chat、qwen-plus 等；字段名固定为 chat-model
    temperature: 0.2
    knowledge-temperature: 0.6   # 知识点精解场景专用温度（DEV_SPEC 6.1 要求答疑 0.2 / 精解 0.6）；知识点生成用独立 ChatModel Bean 读取本键
    max-tokens: 1500
    timeout-seconds: 60
  # 向量模型说明：已采用内置 BGE-Small-ZH 本地量化模型 (纯本地CPU计算，零Token费用，无远程接口依赖)
  chroma:
    base-url: http://${CHROMA_HOST:localhost}:8000
    collection-name: smart_qa_course_docs
  chunk:
    size: 400
    overlap: 50
    similarity-threshold: 0.70
    top-k: 4

file:
  upload-dir: ${user.home}/smartqa/uploads/
```

### 1.3 核心类实现必须遵守的规范
1. **统一全局异常拦截 (`GlobalExceptionHandler.java`)**：
   - 捕获 `BusinessException` 返回 `Result.fail(e.getCode(), e.getMessage())`。
   - 捕获 `MethodArgumentNotValidException` 提取首个校验错误并返回 400。
   - 捕获 `NotLoginException` 返回 401。
   - 捕获 `NotRoleException` 返回 403。
   - 捕获 `Exception` 记录错误日志并返回 `Result.fail(500, "系统繁忙，请稍后重试")`。
2. **异步线程池 (`AsyncThreadPoolConfig.java`)**：
   - 必须配置自定义 `ThreadPoolTaskExecutor`，**严禁直接使用默认公共线程池**。
   - 实际是**两个相互隔离的池**（历史上本规范只写了 `sseExecutor` 一个，并写明「切块也用该池」——**该表述已作废**：拆池是 commit `22f60c5`（09-20）对抗式审查 **[H2]** 的**预防性加固**，目的是让「CPU 密集的课件切块」与「单次可挂 120s 的 SSE 流」不互相争抢、互相饿死，**并非事后补救某已发生的故障**）：

   | Bean 名称（按名注入，**固定不可改**） | 用途 | core / max / queue | 线程名前缀 |
   | :--- | :--- | :--- | :--- |
   | `sseExecutor` | **只**跑 SSE 流式问答（单次可挂 120s） | 10 / 30 / 50 | `sse-worker-` |
   | `ingestExecutor` | **只**跑课件 Tika 切块 + 向量化（CPU / 内存尖峰） | 4 / 8 / 100 | `ingest-worker-` |

   - **两类负载用两种调度机制**（均为现状代码的实际写法，新增代码请沿用对应风格，**选错池等于把两类负载耦合到一起**）：
   ```java
   // SSE 问答流：声明式，由 Spring 代理调度，不需要注入 Executor
   // （见 SseStreamService）
   @Async("sseExecutor")
   public void streamChat(...) { ... }

   // 课件切块 / reindex：手动提交到指定池（见 TeacherDocumentController 的 asyncExecutor 字段）
   // ⚠️ 是 ingestExecutor，不是 sseExecutor
   @Resource(name = "ingestExecutor")
   private Executor asyncExecutor;
   ```
   - **两个池均采用 `ThreadPoolExecutor.AbortPolicy`**：满了直接拒绝，**不得改成 `CallerRunsPolicy`**。`CallerRuns` 会把任务回落到提交线程（Tomcat HTTP 线程）执行，一两个慢流就能把 Tomcat 线程池拖空 → 全站拒绝服务；宁可快速失败返回「服务繁忙」。
   - 池满被拒时**两条路径实际抛的都是 Spring 的 `TaskRejectedException`**（`ThreadPoolTaskExecutor` 的 `execute()`/`submit()` 会把底层 `RejectedExecutionException` 包装一层再抛，`CompletableFuture.runAsync` 走的正是 `Executor.execute()`；`TaskRejectedException` 继承自 `RejectedExecutionException`，故按父类兜底即同时接住两者）。**差异在收尾动作、不在异常类型**（SSE 侧 → 下发 `error` 事件，错码 5003；切块侧 → 把课件置 `FAILED`，**否则永久卡在 `PARSING`**）。
   - 两池均设 `waitForTasksToCompleteOnShutdown(true)`；`awaitTerminationSeconds` 为 sse 30s / ingest 60s，避免优雅停机时在途任务被直接丢弃。
   - **异步线程内取不到登录态**：`sseExecutor` / `ingestExecutor` 的任务体里 Sa-Token 的 ThreadLocal 不可用，用户 ID 必须在请求线程先取好再当参数传入（详见 `QaRecordService` / `QaSessionService` 的相关注释）。
3. **MyBatis-Plus JSON 字段注解**：
   - `qa_record.grounding_references` 在实体类中必须声明为：
     ```java
     @TableField(value = "grounding_references", typeHandler = JacksonTypeHandler.class)
     private List<SseReferenceVO> groundingReferences;
     ```
     并在实体类头部加上 `@TableName(autoResultMap = true)`。

---

## 二、 前端工程实现（面向前端 Agent 任务）

### 2.0 工程根配置 (`vite.config.ts`) —— C/D 两个前端都必须使用此模板
```typescript
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],                       // 必须：否则 Vite 不认识 .vue 文件
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',  // 后端端口固定 8080，见 1.2 application.yml
        changeOrigin: true,
      },
    },
  },
});
```
> ⚠️ 三处不得改动：① `plugins: [vue()]` 缺失则构建直接失败；② 代理路径 `/api` 与 `request.ts` 的 `baseURL` 一致；③ `target` 端口 8080 与后端 `server.port` 一致。SSE 走同一代理即可（`@microsoft/fetch-event-source` 走 HTTP 长连接，Vite 代理默认支持流式转发）。

### 2.1 请求层封装 (`src/utils/request.ts`)
```typescript
import axios from 'axios';
import { ElMessage } from 'element-plus';

const request = axios.create({
  baseURL: '/api',
  timeout: 20000,
});

// 请求拦截器：统一注入鉴权头（只发这一个头即可）
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('satoken');   // 存储键名沿用 satoken，与请求头名无关
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`;   // 头值必须带 Bearer 前缀
  }
  return config;
});

// 响应拦截器
request.interceptors.response.use(
  (response) => {
    const res = response.data;
    if (res.code === 200) {
      return res.data;
    }
    if (res.code === 401) {
      localStorage.removeItem('satoken');
      window.location.href = '/login';
      return Promise.reject(new Error(res.message || '登录已过期'));
    }
    ElMessage.error(res.message || '请求处理失败');
    return Promise.reject(new Error(res.message || 'Error'));
  },
  (error) => {
    ElMessage.error(error.response?.data?.message || '网络通讯异常');
    return Promise.reject(error);
  }
);

export default request;
```

### 2.2 SSE 打字机流式渲染与节流规范 (`ChatWorkspace.vue`)
为了杜绝高频逐字渲染导致浏览器卡死，Agent 必须编写**节流更新机制**：
```typescript
// 节流缓冲区
let tokenBuffer = '';
let renderTimer: number | null = null;

const appendTokenWithThrottle = (token: string) => {
  tokenBuffer += token;
  if (!renderTimer) {
    renderTimer = window.setTimeout(() => {
      currentAiMessage.value.content += tokenBuffer;
      tokenBuffer = '';
      renderTimer = null;
      scrollToBottom();
    }, 60); // 60ms 节流更新一次 DOM
  }
};
```

> 说明：上面引用的 `currentAiMessage`（当前 AI 消息的响应式对象）与 `scrollToBottom()`（滚动到底部）只是占位名称，**需要你在本组件内自行定义**（`const currentAiMessage = ref({ content: '' })`、`const scrollToBottom = () => { /* 容器 scrollTop = scrollHeight */ }`）。核心是节流逻辑本身。

### 2.3 路由守卫与角色控制 (`src/router/index.ts`)
- 未登录用户访问除 `/login` 外的任何路径强制重定向到 `/login`。**本期没有注册功能**，账号由 `data.sql` 种子数据预置（`teacher01` / `student01`）。
- 学生身份访问 `/teacher/**` 路由弹出 `ElMessage.error('无权访问教师管理后台')` 并重定向到学生端工作台 `/student/chat`。

---

## 三、 数据初始化与联调脚手架脚本

### 3.1 初始数据脚本 (`src/main/resources/data.sql`)
```sql
-- 初始化 1 名教师与 2 名学生用户 (初始密码均为 123456，已做 BCrypt 处理: $2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2)
INSERT INTO sys_user (id, username, password, nickname, role) VALUES 
(1, 'teacher01', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '王教授', 'TEACHER'),
(2, 'student01', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '李明', 'STUDENT'),
(3, 'student02', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '张华', 'STUDENT')
ON DUPLICATE KEY UPDATE id=id;

-- 初始化 2 门标杆示范课程
INSERT INTO course (id, course_name, course_code, teacher_id, description) VALUES
(1, '计算机操作系统原理与实践', 'CS202401', 1, '涵盖进程调度、虚拟内存置换、死锁预防与文件系统核心机制。'),
(2, '计算机网络与高并发通信', 'CS202402', 1, '涵盖 TCP/IP 体系结构、拥塞控制算法、DNS 解析与网络安全协议。')
ON DUPLICATE KEY UPDATE id=id;
```

---

## 四、 AI Agent 联调自测准入清单 (Gatekeeper Checklist)

在宣布编码完成前，Agent 必须自行验证通过以下 7 项冒烟测试。**适用时机**：第 1~2 周做单个模块时跑第 1~3 项即可（前端第 2 项）；第 4~7 项依赖登录与 SSE 链路，从第 2 周（Day 11 前后）联调阶段起逐次适用，第 1 周跑不通属正常，不要为了凑清单去造假输出。
1. **编译构建测试**：`mvn clean package -DskipTests` 执行成功，生成 jar 包无报错。
2. **前端类型测试**：必须先跑 `npx vue-tsc --noEmit`（零报错），再跑 `pnpm run build` 或 `npm run build` 成功。**注意：`vite build` 本身不做类型检查，build 通过 ≠ 类型测试通过**，vue-tsc 这一步不能省。
   - ⚠️ **Windows PowerShell 下这两条命令都可能报 `MODULE_NOT_FOUND`**（npm/npx 的 shim 把模块路径解析到 workspace 父目录，与代码无关；CI 在 Ubuntu 上正常）。此时**不要判定为代码有问题、更不要跳过本项**，改用直调模块的等价写法（在 `frontend-student` / `frontend-teacher` 目录内，已实测）：
     ```powershell
     node .\node_modules\vue-tsc\bin\vue-tsc.js --noEmit
     node .\node_modules\vite\bin\vite.js build
     ```
     两者退出码为 0 即等价于本项通过（本条即 §1 第 6 条规则的 ③ 档）。
3. **数据库连接测试**：Spring Boot 启动日志显示 Hikari 连接池初始化成功，表结构自动加载无语法异常。
4. **鉴权闭环测试**：使用 `teacher01` / `123456` 登录成功，拿到 token 并成功访问 `/api/teacher/docs/list`。
5. **文件上传测试**：上传一份带有文字的测试 PDF，控制台无 OOM 异常，文件保存在指定上传路径，状态变为 `CHUNKED`。
6. **SSE 推流测试**：使用 Postman 或浏览器直接发起 GET 请求访问 `/api/qa/chat/stream?courseId=1&sessionId=0&question=测试`，依次收到 `references`、`message`、`done` 格式数据。
7. **问答记录闭环测试**：完成一次提问后，调用 `/api/teacher/qa/records?courseId=1&pageNum=1&pageSize=10`，确认能查到该条记录且包含参考出处；再调用点赞接口，确认 `feedback_rating` 正确更新。
