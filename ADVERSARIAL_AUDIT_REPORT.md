# 课程设计全文档红蓝对抗性审查报告 (ADVERSARIAL_AUDIT_REPORT.md)
## AI 驱动的在线学习智能答疑辅导平台（RAG 知识库）

> **审计背景**：针对面向 AI Agent 自动化生成代码与 4 人本科生团队全栈交付方案的全面红蓝对抗性审查。  
> **审计基准**：4 人协同零摩擦、接口零猜测、RAG 核心闭环零死锁、答辩演示零翻车。

> **✅ 补丁落地状态（v1.1 复审）**：本报告中缺陷 1~5、漏洞 1~4 的修复方案**已全部回写进各源头文档并冻结为唯一契约**：SSE `done` 包已含 `recordId`（成员 A 指南代码）、纠偏优先双路检索已并入 `SseStreamService`（缺陷 3）、级联删除向量已提供 `removeDocumentVectors`（A/B 指南，缺陷 4）、会话懒创建规则已写入 `DEV_SPECIFICATION` 4.2（缺陷 5）、Sa-Token `Bearer` 前缀与 `satoken` 存储键已全团队统一（缺陷 1）、专用 `sseExecutor` 线程池强制注入（漏洞 1）、渲染节流见 AGENT_INSTRUCTIONS 2.2（漏洞 2）、`file.upload-dir` 绝对路径（漏洞 3）、`JacksonTypeHandler` + `autoResultMap`（漏洞 4）。**执行各成员任务时以回写后的文档为准，本报告仅作背景参考，不要再按"未修复前的旧代码"生成。**

---

## 目录
1. [跨成员接口与协同死锁（5 大致命隐患与补丁）](#一跨成员接口与协同死锁5-大致命隐患与补丁)
2. [系统工程与生产可靠性漏洞（4 大暴雷点与对策）](#二系统工程与生产可靠性漏洞4-大暴雷点与对策)
3. [AI Agent 开发特异性断层（4 个必须补齐的输入）](#三ai-agent-开发特异性断层4-个必须补齐的输入)
4. [答辩现场评委刁钻连环追问与抗辩预案（防挂科指南）](#四答辩现场评委刁钻连环追问与抗辩预案防挂科指南)

---

## 一、跨成员接口与协同死锁（5 大致命隐患与补丁）

### 🚨 缺陷 1：Sa-Token 鉴权 Header 与 SSE 请求规范冲突（联调直接 401 瘫痪）
* **涉及成员**：成员 B（鉴权） vs 成员 C（前端流式客户端）
* **冲突事实**：
  - 在 `MEMBER_B_DEV_GUIDE.md` 中，Sa-Token 默认配置从请求头 `satoken: <token>` 中提取认证信息，拦截器配置：`SaRouter.match("/api/qa/**", r -> StpUtil.checkLogin())`。
  - 在 `MEMBER_C_DEV_GUIDE.md` 中，前端 `fetchEventSource` 发送的是标准 JWT 请求头：`headers: { 'Authorization': 'Bearer ' + token }`。
* **致命后果**：
  - 成员 C 调用 `/api/qa/chat/stream` 时，后端 Sa-Token 根本拿不到 token，**所有智能问答流式请求 100% 返回 401 Unauthorized**。如果联调时排查不出，前后端会互相指责对方接口写错。
* **修复补丁**：
  - 成员 B 必须在 `application.yml` 中配置兼容 Header，或配置 token 前缀支持 `Bearer`：
  ```yaml
  sa-token:
    token-name: satoken
    is-read-header: true
    is-read-cookie: false
    token-prefix: Bearer # 允许自动剥离 Bearer 前缀并兼容 Authorization 标头
  ```

---

### 🚨 缺陷 2：SSE `done` 结束包未返回 `recordId`（问答评价与纠偏完全无法串联）
* **涉及成员**：成员 A（推流） vs 成员 B（存储） vs 成员 C（前台评价）
* **冲突事实**：
  - `MEMBER_A_DEV_GUIDE.md` 中，流式结束时推送：
    ```java
    emitter.send(SseEmitter.event().name("done").data(Map.of(
        "sessionId", sessionId, "finishReason", "stop"
    )));
    ```
  - 但在 `MEMBER_B` 和 `MEMBER_C` 的规范中，学生点赞/点踩接口是：`POST /api/qa/records/{id}/feedback`，需要明确的 `recordId`。
* **致命后果**：
  - 前端收到 `done` 后，只有 `sessionId`，**根本不知道刚刚生成的问答记录在数据库里的 `recordId` 是多少**。学生点击“点赞”或“点踩”时无法向后端传参，前端评价功能报废。
* **修复补丁**：
  - 成员 A 必须在 `done` 触发前，调用成员 B 暴露的持久化逻辑并拿到自增/雪花 `recordId`，在 `done` 包中下发：
    ```java
    Long recordId = qaRecordService.saveStreamResult(courseId, sessionId, question, fullAnswer.toString(), matches);
    emitter.send(SseEmitter.event().name("done").data(Map.of(
        "recordId", recordId,
        "sessionId", sessionId,
        "finishReason", "stop"
    )));
    ```

---

### 🚨 缺陷 3：教师人工纠偏与 RAG 检索链路割裂（“假闭环”：改了白改）
* **涉及成员**：成员 D（纠偏前台） vs 成员 B（纠偏落库） vs 成员 A（RAG 检索）
* **冲突事实**：
  - 成员 D 界面提供了教师修改答案的弹窗，成员 B 提供了 `/api/teacher/qa/correct` 将修改结果写入 `qa_record.corrected_answer` 并标记 `is_corrected = 1`。
  - **但是，成员 A 的 `RagRetrievalService` 依然只去检索 Chroma 向量数据库中的课件切块**！根本没有去查 `qa_record` 表！
* **致命后果**：
  - 教师在后台纠偏了“进程与线程的区别”，下一个学生在前端再问同样的问题时，**AI 依然基于原课件输出之前的错误回答**。所谓的“人工纠偏与监督微调”成为假功能，答辩演示时必露馅！
* **修复补丁（双路检索策略）**：
  - 成员 A 在检索向量库前，增加一层**纠偏库精准/语义快搜**：
    ```java
    // 优先匹配教师已纠偏的问题 (支持关键词/余弦距离最高且 is_corrected=1)
    Optional<QaRecord> corrected = qaRecordMapper.findTopCorrected(courseId, question);
    if (corrected.isPresent()) {
        // 直接下发纠偏标准答案，标记出处为“任课教师权威修正”
        emitCorrectedDirectly(emitter, corrected.get());
        return;
    }
    // 未命中纠偏记录，再走普通 RAG 课件检索
    ```

---

### 🚨 缺陷 4：课件删除后，Chroma 向量库未级联删除（“幽灵切块”脏数据）
* **涉及成员**：成员 B（删除课件） vs 成员 A（向量库）
* **冲突事实**：
  - 成员 D/B 实现了课件在 MySQL 中的删除：`DELETE FROM course_document WHERE id = ?` 或标记软删除。
  - 但 Chroma 向量数据库中的向量片段并没有被删除！
* **致命后果**：
  - 老师如果删除了包含旧版教学大纲或错误考点的课件，学生提问时，**系统依然会从 Chroma 召回已被删除的废弃课件切片**，并在前端出处抽屉里展示该已删课件，造成“幽灵参考资料”。
* **修复补丁**：
  - 成员 A 在 `DocumentIngestionService` 中补充物理级联清理方法：
    ```java
    public void removeDocumentVectors(Long docId) {
        // 基于元数据 docId 过滤并清理 Chroma 向量集合
        embeddingStore.removeAll(new IsEqualTo("docId", String.valueOf(docId)));
    }
    ```
  - 成员 B 在执行文档删除业务时，级联调用该方法。

---

### 🚨 缺陷 5：会话 `sessionId` 创生时机混乱，外键约束或历史关联断裂
* **涉及成员**：成员 C（发起新会话） vs 成员 A（流式答疑）
* **冲突事实**：
  - 前端刚进入页面时，学生尚未创建会话。此时如果直接在输入框提问，前端传给 `/api/qa/chat/stream` 的 `sessionId` 是 `null`、`0` 还是事先发请求创建？
* **修复补丁**：
  - **统一规范**：采用“接口懒加载创生”模式。若前端传 `sessionId=0` 或为空，后端自动在 `qa_session` 插入一条记录，标题截取提问前 15 个字符，并在首包或 `done` 中返回新 `sessionId`。

---

## 二、系统工程与生产可靠性漏洞（4 大暴雷点与对策）

### ⚡ 漏洞 1：`CompletableFuture.runAsync` 默认公共线程池耗尽
* **隐患**：成员 A 的代码使用了 `CompletableFuture.runAsync(() -> { ... })`。在没有传入自定义线程池时，默认使用的是 JVM 的 `ForkJoinPool.commonPool()`，其核心线程数等于 `CPU核数 - 1`。
* **暴雷场景**：答辩现场若有多位同学同时发问，由于大模型流式生成需要持续占用线程 20~60 秒，**后续提问将直接阻塞排队，导致整个后端响应冻结**！
* **对策**：必须在 `RagConfig` 中显式定义专门的业务虚拟线程池或异步线程池：
  ```java
  @Bean(name = "sseExecutor")
  public Executor sseExecutor() {
      ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
      executor.setCorePoolSize(10);
      executor.setMaxPoolSize(30);
      executor.setQueueCapacity(50);
      executor.setThreadNamePrefix("sse-worker-");
      executor.initialize();
      return executor;
  }
  ```

---

### ⚡ 漏洞 2：流式 Markdown 逐字全量重绘导致页面严重掉帧
* **隐患**：成员 C 的 `MarkdownViewer.vue` 中，每次大模型吐一个 token，就会触发 `sanitizeHtml` 和 `md.render()`，重新解析全量 HTML 字符串并重新挂载 DOM。
* **暴雷场景**：当回答到达 800 字以上时，随着 token 密集到达，浏览器主线程被高频全量 DOMPurify 和 Highlight.js 占满，**打字机产生肉眼可见的卡顿与打字停滞，甚至浏览器标签页假死崩溃**。
* **对策**：在前端增加**渲染节流（Throttle）**机制（50ms ~ 80ms 刷新一次渲染）。

---

### ⚡ 漏洞 3：大课件 Tika 解析内存溢出（OOM）与相对路径持久化丢失
* **隐患**：`MEMBER_B` 将上传文件保存至 `uploads/` 相对路径。在 Spring Boot 打包成 jar 包运行或重启容器时，相对路径下的文件将全部丢失。
* **对策**：必须在 `application.yml` 中配置绝对路径，如 `file.upload-dir: ${user.home}/smartqa/uploads/`。

---

### ⚡ 漏洞 4：MySQL 8.0 `JSON` 字段在 MyBatis-Plus 中序列化异常
* **隐患**：`qa_record` 表中的 `grounding_references` 定义为 `JSON` 类型。如果实体类中声明为 `List<SseReferenceVO>`，MyBatis-Plus 默认无法直接持久化，查询时抛出 `DataTruncation` 或 `TypeException`。
* **对策**：实体类字段必须加上注解：
  ```java
  @TableField(value = "grounding_references", typeHandler = JacksonTypeHandler.class)
  private List<SseReferenceVO> groundingReferences;
  ```
  并在类头上加上 `@TableName(autoResultMap = true)`。

---

## 三、AI Agent 开发特异性断层（4 个必须补齐的输入）

由于你们主要依靠 **AI Agent（如 Cursor, Windsurf, Claude Code 等）** 进行自动化代码编写，Agent 并不具备人类开发者的“经验隐式推断”能力。必须提供以下 4 类物料以防 Agent 乱写：
1. **系统级统一指令库 (`AGENT_INSTRUCTIONS.md`)**：明确禁止 Agent 引入微服务全家桶、严格限制类库版本。
2. **完整配置模板 (`application.yml`)**：提供开箱即用的配置，包含 Sa-Token、数据源、LLM API 与线程池参数。
3. **初始 SQL 数据脚本 (`data.sql`)**：预置 1 位老师、2 位学生测试账号与 2 门示范课程，方便 Agent 跑通冒烟自测。
4. **Agent 准入验证清单 (Gatekeeper Checklist)**：涵盖编译、类型检查、SSE 推流验证等 7 大检查项。

---

## 四、答辩现场评委刁钻连环追问与抗辩预案（防挂科指南）

| 评委刁钻提问 | 脆弱回答（易被扣分） | 优秀标准抗辩（高分示范） |
| :--- | :--- | :--- |
| **Q1：大模型有严重幻觉，课件里没有的内容它胡说八道怎么办？** | “我们用的模型很聪明，应该不会乱说。”（扣分） | “我们在系统层设计了三道防线：第一道是**元数据隔离检索**（Cosine 相似度严格限定 `>= 0.70`，低分片段直接丢弃）；第二道是**强约束系统提示词**，硬性规定未检索到时首句必须回答‘课件未提及’；第三道是**教师人工纠偏机制**，对负反馈问题进行覆盖纠偏。” |
| **Q2：如果两个老师上传了同名课件，切块怎么隔离？学生提问怎么防止串课？** | “我们按照文件名区分的。”（扣分） | “我们在 Chroma 存储切块时，强制注入了租户级复合元数据：`courseId + docId + chunkIndex`。检索时在底层执行了强制的 Filter 条件过滤，彻底杜绝了不同课程间的跨域串流。” |
| **Q3：学生中途关闭网页或切换了题目，后端大模型还在消耗 Token 怎么处理？** | “没注意，关了就关了吧。”（扣分） | “我们在前端封装了基于 `AbortController` 的断流机制，一旦页面销毁或切换立即 `abort()`；后端 `SseEmitter` 监听了 `onCompletion` 和 `onTimeout` 回调，检测到客户端断开连接立即中断调用，防止算力与 Token 浪费。” |
