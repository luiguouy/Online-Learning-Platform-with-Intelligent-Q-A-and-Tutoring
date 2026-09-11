# AI 驱动的在线学习智能答疑辅导平台（RAG 课程知识库）
## AI Agent 执行指令清单与防坑实战手册 (AGENT_INSTRUCTIONS.md)

> **文档性质**：面向自动化编程 Agent（如 Cursor, Windsurf, Claude Code, GitHub Copilot Workspace, Qwen-Code 等）的系统提示与工程约束指南。  
> **核心目标**：消除 Agent 编码时的“自由发挥与过度设计”，确保一次性生成可用、可编译、可联调的生产级 Java + Vue3 代码。

---

## 零、 AI Agent 核心原则与禁令（System Constraints）

1. **严格单一工程，禁止拆分微服务**：
   - 严禁引入 Spring Cloud、Nacos、Eureka、Feign、Dubbo、Seata。
   - 必须采用**单体 Spring Boot 3.x** 应用，包名统一为 `com.smartqa.platform`。
2. **禁止擅自新增未约定的第三方依赖**：
   - 仅允许使用 `pom.xml` 与 `package.json` 中明确列出的库。严禁引入无意义的通用工具库或废弃库。
3. **接口返回格式红线**：
   - 普通 RESTful 接口必须严格返回 `com.smartqa.platform.common.Result<T>`。
   - 智能答疑 SSE 接口必须严格输出 `text/event-stream`，且只能包含 `references`、`message`、`done`、`error` 4 种事件类型。
4. **禁止空桩代码（No Mock Stubs）**：
   - 严禁在 Service 中写 `return null;` 或 `// TODO: implement later`。
   - 必须实现完整的数据库查询、异常抛出与逻辑校验。
5. **绝对禁止硬编码 API 密钥**：
   - 所有 LLM Key、数据库密码必须通过 `${SPRING_DATASOURCE_PASSWORD}` 或 `${AI_API_KEY}` 环境变量注入。

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
│   ├── SaTokenConfig.java           // Sa-Token 路由拦截器与权限配置
│   ├── CorsConfig.java              // WebMvc 跨域配置
│   ├── AsyncThreadPoolConfig.java   // 异步与SSE线程池配置
│   └── LangChain4jConfig.java       // LLM、EmbeddingModel、EmbeddingStore Bean
├── controller/
│   ├── AuthController.java          // /api/auth/*
│   ├── CourseController.java        // /api/course/*
│   ├── SseChatController.java       // /api/qa/chat/stream (SSE)
│   ├── QaRecordController.java      // /api/qa/*
│   └── TeacherManageController.java // /api/teacher/*
├── service/
│   ├── SysUserService.java
│   ├── CourseService.java
│   ├── CourseDocumentService.java
│   ├── QaSessionService.java
│   ├── QaRecordService.java
│   └── rag/
│       ├── DocumentIngestionService.java // 文档切片与入库
│       └── RagRetrievalService.java      // 双路召回与上下文组装
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
  token-name: satoken
  timeout: 604800 # 7天免登录
  active-timeout: 86400
  is-concurrent: true
  is-share: true
  token-style: uuid
  is-read-header: true
  is-read-cookie: false
  token-prefix: Bearer # 关键修复：允许读取 Authorization: Bearer <token>

rag:
  llm:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1 # 兼容 OpenAI 格式
    api-key: ${AI_API_KEY:sk-placeholder}
    model-name: qwen-plus
    temperature: 0.2
    max-tokens: 1500
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
   - 必须配置自定义 `ThreadPoolTaskExecutor` 供 SSE 推流使用，严禁直接使用默认公共线程池。
3. **MyBatis-Plus JSON 字段注解**：
   - `qa_record.grounding_references` 在实体类中必须声明为：
     ```java
     @TableField(value = "grounding_references", typeHandler = JacksonTypeHandler.class)
     private List<SseReferenceVO> groundingReferences;
     ```
     并在实体类头部加上 `@TableName(autoResultMap = true)`。

---

## 二、 前端工程实现（面向前端 Agent 任务）

### 2.1 请求层封装 (`src/utils/request.ts`)
```typescript
import axios from 'axios';
import { ElMessage } from 'element-plus';

const request = axios.create({
  baseURL: '/api',
  timeout: 20000,
});

// 请求拦截器
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('satoken');
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`;
    config.headers['satoken'] = token;
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

### 2.3 路由守卫与角色控制 (`src/router/index.ts`)
- 未登录用户访问除 `/login`、`/register` 外的任何路径强制重定向到 `/login`。
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

在宣布编码完成前，Agent 必须自行验证通过以下 7 项冒烟测试：
1. **编译构建测试**：`mvn clean package -DskipTests` 执行成功，生成 jar 包无报错。
2. **前端类型测试**：`pnpm run build` 或 `npm run build` 执行成功，无 TypeScript 类型错误。
3. **数据库连接测试**：Spring Boot 启动日志显示 Hikari 连接池初始化成功，表结构自动加载无语法异常。
4. **鉴权闭环测试**：使用 `teacher01` / `123456` 登录成功，拿到 token 并成功访问 `/api/teacher/docs/list`。
5. **文件上传测试**：上传一份带有文字的测试 PDF，控制台无 OOM 异常，文件保存在指定上传路径，状态变为 `CHUNKED`。
6. **SSE 推流测试**：使用 Postman 或浏览器直接发起 GET 请求访问 `/api/qa/chat/stream?courseId=1&sessionId=0&question=测试`，依次收到 `references`、`message`、`done` 格式数据。
7. **双路纠偏闭环测试**：调用纠偏接口后，再次提问相同问题，确认系统优先返回教师人工修正的答案。
