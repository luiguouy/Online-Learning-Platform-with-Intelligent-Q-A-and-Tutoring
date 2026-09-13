# 成员 B 后端代码 · 使用说明与接口契约

> 对应文档：`dev-docs/MEMBER_B_DEV_GUIDE.md`、`dev-docs/THREE_WEEK_PLAN.md`、`dev-docs/DEV_SPECIFICATION.md`
> 代码位置：本目录即 `backend/` 工程根目录，可直接复制进仓库 `backend/` 下。
> 技术栈：Spring Boot 3.3.5 + JDK 17 + MySQL 8 + MyBatis-Plus 3.5.7 + Sa-Token 1.38.0 + Knife4j 4.5.0

---

## 一、5 分钟跑起来

### 1. 建库导数据

```bash
mysql -u root -p < src/main/resources/db/schema.sql
mysql -u root -p smart_qa < src/main/resources/db/data.sql
```

`schema.sql` 末尾会打印 6 张表，缺一张就是导入出错，回去看报错。

种子账号（密码都是 `123456`，BCrypt strength=10，已逐个校验通过）：

| 账号 | 角色 | 说明 |
| :--- | :--- | :--- |
| `teacher01` | TEACHER | 教师，名下挂 3 门课（id = 1/2/3） |
| `student01` | STUDENT | 学生 |
| `student02` | STUDENT | 学生 |

### 2. 配数据库密码

环境变量注入（推荐），或复制 `dev-docs/templates/application-example.yml` 为
`src/main/resources/application-local.yml`：

```bash
export MYSQL_PASSWORD=你的密码
export AI_API_KEY=sk-xxxx      # 成员 A 用，B 的模块不依赖
```

**绝对不要把真实 Key 或生产密码写进 application.yml 并提交。**

### 3. 编译与启动

```bash
mvn clean compile        # 验收命令：必须看到 BUILD SUCCESS
mvn spring-boot:run
```

打开 <http://localhost:8080/doc.html> 看接口文档。

### 4. ⚠️ 启动前必读：与成员 A 的边界

`TeacherDocumentController` 依赖成员 A 的 `DocumentIngestionService` 实现（A1.4 / A1.5）。

- **`mvn clean compile` 能通过** —— 编译只依赖接口，不依赖实现，验收命令照跑。
- **`mvn spring-boot:run` 在 A 交付实现前会启动失败**，报
  `NoSuchBeanDefinitionException: DocumentIngestionService`。**这是预期的边界，不是 Bug。**

在你需要**单独联调 B 的模块**（比如提前给 D 演示上传/删除/教师查记录）时，
临时放一个只用于本地跑通的实现类、联调完删掉即可；**不要把这个临时类提交到 `dev` 分支**。

---

## 二、文件清单（按包分层）

```
backend/
├── pom.xml
└── src/main/
    ├── java/com/smartqa/platform/
    │   ├── SmartQaApplication.java          启动类（@MapperScan 在这）
    │   ├── common/
    │   │   ├── Result.java                  统一响应包装，所有接口必须返回它
    │   │   ├── BusinessException.java       业务异常（含 code 字段）
    │   │   └── GlobalExceptionHandler.java  401/403/400/404/429/500 分档处理
    │   ├── config/
    │   │   ├── MybatisPlusConfig.java       分页插件（不配 IPage 分页会失效）
    │   │   ├── AsyncThreadPoolConfig.java   sseExecutor 专用线程池（Bean 名固定）
    │   │   └── SaTokenConfigure.java        路由鉴权 + 限流拦截器注册
    │   ├── auth/
    │   │   └── StpInterfaceImpl.java        ★ 不实现则教师端全部 403
    │   ├── interceptor/
    │   │   └── QaRateLimitInterceptor.java  每用户 60s / 20 次
    │   ├── entity/                          6 张表 6 个实体（都带 @TableLogic）
    │   ├── mapper/                          6 个 BaseMapper
    │   ├── dto/                             LoginDTO、FeedbackDTO
    │   ├── vo/                              LoginVO、SessionVO、SseReferenceVO
    │   ├── rag/
    │   │   └── DocumentIngestionService.java  ★ 成员 A 的实现契约（B 只调用）
    │   ├── service/ + service/impl/         业务接口与实现
    │   └── controller/                      5 个控制器
    └── resources/
        ├── application.yml
        └── db/{schema.sql, data.sql}
```

---

## 三、接口契约（成员 C / D 照此调用）

**通用约定**

- 所有接口返回 `Result<T>`：`{ code, message, data, timestamp }`
- code：`200` 成功 / `400` 业务或参数错误 / `401` 未登录 / `403` 无权 / `404` 不存在 / `429` 限流 / `500` 系统异常
- **HTTP 状态码与 body 里的 code 一致**（与限流拦截器写法统一）。前端两处都能判断：

```ts
// 前端请求层建议写法（axios）
http.interceptors.response.use(
  (res) => {
    if (res.data.code !== 200) return Promise.reject(new Error(res.data.message));
    return res.data.data;
  },
  (err) => {
    const body = err.response?.data;
    if (body?.code === 401) {          // 未登录：清 token 跳登录页
      localStorage.removeItem('satoken');
      router.push('/login');
    }
    return Promise.reject(new Error(body?.message || '网络异常'));
  }
);
```

- **请求头只有一个**：`Authorization: Bearer <token>`（`Bearer` + 一个空格，缺了就是 401）

### 3.1 登录

```
POST /api/auth/login
Content-Type: application/json

{ "username": "student01", "password": "123456" }
```

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "token": "xxxx-xxxx-xxxx",
    "userId": 2,
    "username": "student01",
    "nickname": "张小明",
    "role": "STUDENT",
    "avatarUrl": ""
  },
  "timestamp": 1757680000000
}
```

前端把 `token` 存 `localStorage`（键名 `satoken`），`role` 存 store 用于路由守卫。
**本期没有注册接口，不要找 `/api/auth/register`。**

### 3.2 课程列表

```
GET /api/course/list          Authorization: Bearer <token>
```

返回 `data` 为课程数组，元素字段：
`id`、`courseName`、`courseCode`、`teacherId`、`description`、`coverImage`、`createdAt`、`updatedAt`

教师只返回自己任课的课程；学生返回全部（本期无选课关系表，已在代码注释里说明）。

### 3.3 教师端 · 课件管理（成员 D）

```
POST   /api/teacher/docs/upload        multipart/form-data
       courseId=1  file=<PDF/DOCX/MD/TXT>
       → data: 1（课件 ID），状态已置为 PARSING

GET    /api/teacher/docs/list?courseId=1
       → data: CourseDocument[]

DELETE /api/teacher/docs/{id}          → data: true

POST   /api/teacher/docs/{id}/reindex  → data: true（异步，状态先回 PARSING）
```

`CourseDocument` 字段：`id`、`courseId`、`fileName`、`filePath`、`fileSize`、`fileType`、
`chunkCount`、`parseStatus`、`errorMsg`、`createdAt`、`updatedAt`

`parseStatus` 只有四个值，前端标签按这个映射，**不要用别的别名**：

| 值 | 展示文案 | 前端行为 |
| :--- | :--- | :--- |
| `PENDING` | 排队中 | 正常流程基本看不到，但标签要能渲染 |
| `PARSING` | 切块向量化中 | 每 3 秒轮询 `/list` |
| `CHUNKED` | 已就绪 | 停止轮询 |
| `FAILED` | 解析失败 | 停止轮询，把 `errorMsg` 显示为 tooltip |

**删除课件会自动级联清理向量库**，前端不需要额外调什么。

### 3.4 学生端 · 问答记录（成员 C）

```
GET  /api/qa/sessions?courseId=1
     → data: SessionVO[]   { id, courseId, sessionTitle, createdAt, updatedAt }

GET  /api/qa/records?sessionId=1
     → data: QaRecord[]    按提问时间正序，直接从上往下渲染对话

POST /api/qa/records/{id}/feedback
     body: { "status": 1 }   1 点赞 / -1 点踩
     → data: true
```

`QaRecord` 关键字段：

| 字段 | 说明 |
| :--- | :--- |
| `id` | 记录 ID，来自 SSE `done` 事件的 `recordId` |
| `question` / `answer` | 提问原文 / AI 回答（Markdown） |
| `groundingReferences` | 出处快照数组，元素同 `SseReferenceVO` |
| `feedbackRating` | 1 / -1 / 0，用于回显点赞按钮选中态 |
| `latencyMs` | 生成耗时 |

**越权保护已做**：非本人会话返回 403，学生看不到别人的提问历史。

### 3.5 教师端 · 问答记录查看（成员 D）

```
GET /api/teacher/qa/records?courseId=1&pageNum=1&pageSize=10&keyword=LRU
```

```json
{
  "code": 200,
  "data": {
    "records": [ { "id": 1, "question": "...", "answer": "...",
                   "groundingReferences": [ ... ], "feedbackRating": 1,
                   "latencyMs": 1840, "createdAt": "..." } ],
    "total": 1, "size": 10, "current": 1, "pages": 1
  }
}
```

- `keyword` 同时匹配 `question` 与 `answer`，可不传
- `pageSize` 服务端上限 100
- **本接口纯只读**：没有任何修改 AI 回答的入口，页面上也不要放编辑按钮（人工纠偏已裁掉）

---

## 四、给成员 A 的冻结契约（Day 3 死线）

### 4.1 B 交付给 A 的两个方法

```java
// QaRecordService —— 流式推流结束后保存记录
Long saveStreamingRecord(Long courseId, Long sessionId, String question,
                         String answer, List<SseReferenceVO> references, long latencyMs);
// ★ 返回值不能为 null：A 会写进 Map.of("recordId", recordId, ...)，Map.of 不接受 null

// QaSessionService —— sessionId=0 时懒创建会话
Long createSessionLazy(Long courseId, String question);
// ★ 返回值不能为 null：A 用它替换 done 包里的 sessionId
```

> **在 `sseExecutor` 异步线程里调用时请注意**：Sa-Token 的登录态存在 ThreadLocal，
> 异步线程里 `StpUtil.isLogin()` 为 false。这两个方法会抛出带指引的 401，而不是 NPE。
> 异步场景请改用显式传 userId 的重载：
> `saveStreamingRecordAs(userId, courseId, sessionId, question, answer, references, latencyMs)` 和
> `createSessionLazyForUser(userId, courseId, question)` —— **在请求线程里先把 userId 取好再传下去。**

### 4.2 B 需要 A 提供的方法

```java
public interface DocumentIngestionService {
    int  processAndEmbedDocument(InputStream in, Long courseId, Long docId, String fileName) throws Exception;
    void removeDocumentVectors(Long docId);
}
```

- B 负责把课件落盘、落库、把状态推到 `PARSING`，然后**在 sseExecutor 线程池里异步调用**；
- 切块成功/失败后的状态回写**由 B 自己的异步块完成**，A 不需要回调任何方法；
- 若 A 在别的包下已有同名接口，**删掉 B 目录下的这份、保留 A 的**，方法签名必须一致。

---

## 五、限流与边界行为（答辩会用到的点）

| 场景 | 行为 |
| :--- | :--- |
| 同一用户 60 秒内第 21 次提问 | HTTP 429，`{"code":429,"message":"提问过于频繁，请稍后再试"}` |
| 未登录访问 `/api/qa/chat/stream` | HTTP 401（**不是 500**，拦截器先判 `isLogin()`） |
| 学生 token 访问 `/api/teacher/**` | HTTP 403 |
| 学生查别人的 `sessionId` | HTTP 403 |
| 教师删别人课程的课件 | HTTP 403 |
| 上传 `.exe` | HTTP 400 "仅支持 PDF / DOCX / MD / TXT 格式" |
| 文件名含 `../../` | HTTP 400 "文件名非法"（已剥离路径，防目录穿越） |
| 上传超过 50MB | HTTP 400 "文件超过 50MB 上限" |
| 反馈 `status` 传 0 或 2 | HTTP 400 "反馈状态只能是 1（点赞）或 -1（点踩）" |
| AI 回答里带 SQL / 磁盘路径的异常 | 只回 `系统繁忙，请稍后重试`，堆栈只进日志 |

---

## 六、交给 AI 的下一条指令模板

```text
请先完整阅读我提供的 3 份文档，严格遵照执行，不允许自由发挥：
1. AGENT_INSTRUCTIONS.md（全局禁令与配置模板，最高优先级）
2. DEV_SPECIFICATION.md（编码与接口规范）
3. MEMBER_B_DEV_GUIDE.md（你负责模块的详细设计）

我现在的任务是：B2.1 课件上传接口 + 状态机（代码已存在于
TeacherDocumentController，请帮我复核并跑通验收标准）。
先列出你将创建/修改的文件清单让我确认，再开始写代码。
每次写完后，对照 AGENT_INSTRUCTIONS.md 第四章的 7 项冒烟清单自查并报告结果。
```

---

## 七、提交前自查（对应 B 的验收表）

- [ ] `mvn clean compile` → `BUILD SUCCESS`
- [ ] `schema.sql` 导入无报错，`show tables` 有 6 张表
- [ ] `teacher01/123456` 能登录拿到 token
- [ ] 教师 token 调 `/api/teacher/docs/list?courseId=1` 不报 403
- [ ] `/doc.html` 能看到 5 个分组的接口文档
- [ ] 上传的文件落在 `${user.home}/smartqa/uploads/1/` 下（绝对路径）
- [ ] `git status` 里没有 `application-local.yml`、没有真实 Key
