# 成员 A 详细开发文档：AI 与 RAG 核心算法工程师

> **角色**：成员 A（AI / RAG 核心算法工程师 · 后端组长）  
> **职责模块**：Spring Boot 工程底座、LangChain4j 接入、课件解析与向量化存储、RAG 检索增强、SSE 流式智能答疑接口、知识点结构化生成  
> **适用技术栈**：Spring Boot 3.x + LangChain4j 0.35+ + Chroma DB / 本地向量库 + 阿里云百炼/DeepSeek API + SSE (Server-Sent Events)

---

## 一、 模块定位与工程职责边界

成员 A 是整个系统的**“智能大脑枢纽”**，主要负责打通从**课件非结构化数据输入**到**高质量精准流式输出**的核心链路：

1. **工程骨架与基础脚手架**：初始化 Spring Boot 3.x 统一工程，配置 Maven 依赖管理。
2. **文档解析与切块（Document Ingestion）**：支持 PDF、Markdown、TXT 格式课件解析，执行递归字符切片（Chunk Size = 400，Overlap = 50）。
3. **向量化与存储（Embedding & Vector Store）**：使用通用文本向量模型（如 `text-embedding-v3` 或本地 BGE-small），元数据（courseId、docId、fileName）关联注入，存入 Chroma 或内存向量库。
4. **多阶段检索与防幻觉调优**：按 `courseId` 租户级过滤，Cosine 相似度 `>= 0.70` 过滤，召回 Top-K（3~4），组装严谨防幻觉 System Prompt。
5. **SSE 流式智能答疑接口**：对外提供 `GET /api/qa/chat/stream`，按照 4 阶段协议（`references` -> `message` -> `done` -> `error`）向前端打字机推流。
6. **知识点解析**：提供 `POST /api/knowledge/generate` 接口，返回结构化 Markdown 精解（核心概念定义 + 难点辨析），**不生成自测题**。
   - ⚠️ **必须加 Sa-Token 登录校验**（`StpUtil.checkLogin()`）。该接口单次调用消耗大量 Token，不鉴权会被恶意刷爆额度——这也是评委常问的"成本如何控制"。
   - ⚠️ 同时必须做**按用户限流**（实现见 `MEMBER_B_DEV_GUIDE.md` 4.6）。

---

## 二、 Maven 依赖与核心配置

### 2.1 `pom.xml` 核心依赖清单
```xml
<dependencies>
    <!-- Spring Boot 核心 Web 与验证 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- LangChain4j 核心与 Spring Boot 整合 -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-spring-boot-starter</artifactId>
        <version>0.35.0</version>
    </dependency>
    <!-- OpenAI 兼容接口驱动 (支持 DeepSeek / 通义千问 / 月之暗面 / Ollama) -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>0.35.0</version>
    </dependency>
    <!-- Chroma 向量数据库适配器 -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-chroma</artifactId>
        <version>0.35.0</version>
    </dependency>
    <!-- Apache Tika (文档智能解析支持 PDF, DOCX, TXT) -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-document-parser-apache-tika</artifactId>
        <version>0.35.0</version>
    </dependency>

    <!-- Lombok 工具 -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

### 2.2 `application.yml` 配置规范
```yaml
server:
  port: 8080
  servlet:
    context-path: /

rag:
  llm:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1 # 阿里云百炼或 DeepSeek API
    api-key: ${AI_API_KEY:sk-placeholder} # 生产使用环境变量注入
    chat-model: qwen-plus # 或 deepseek-chat
    embedding-model: text-embedding-v3
    temperature: 0.2
    max-tokens: 1500
    timeout-seconds: 60
  chroma:
    base-url: http://${CHROMA_HOST:localhost}:8000 # Chroma Docker 地址
    collection-name: smart_qa_course_docs # 全团队统一，禁止改名
  chunk:
    size: 400
    overlap: 50
    similarity-threshold: 0.70
    top-k: 4
```

> **配置字段唯一真源**：`rag.*` 字段名以 `AGENT_INSTRUCTIONS.md` 1.2 节为唯一标准（两处已同步）。模型字段名固定为 **`chat-model`**，**严禁写成 `model-name`**——字段名不一致会导致 `@ConfigurationProperties` 绑定失败并静默回退默认值，排查成本极高。

---

## 三、 模块代码结构与详细类设计

```text
com.smartqa.platform.rag
├── config/
│   ├── RagConfigProperties.java       // RAG参数映射实体
│   └── LangChain4jConfig.java         // LLM、EmbeddingModel、EmbeddingStore Bean装配
├── constant/
│   └── PromptConstants.java           // 系统级防幻觉提示词模板
├── controller/
│   ├── SseChatController.java         // /api/qa/chat/stream 控制器
│   └── KnowledgeController.java        // /api/knowledge/generate 控制器
├── service/
│   ├── DocumentIngestionService.java  // 课件文档解析与切片向量化服务
│   ├── RagRetrievalService.java       // 向量相似度检索与上下文装配服务
│   └── SseStreamService.java          // SSE流式推送控制服务
└── model/
    ├── SseReferenceVO.java            // 检索溯源首包VO
    ├── KnowledgeGenerateDTO.java      // 知识点解析请求DTO
    └── KnowledgeGenerateVO.java       // 知识点解析响应VO
```

---

## 四、 核心业务逻辑实现指南

### 4.1 文档切块与元数据注入 (`DocumentIngestionService.java`)
**实现要点**：
- 输入：`MultipartFile file`, `Long courseId`, `Long docId`
- 解析：使用 Tika 将 PDF/Markdown 转换为纯净文本并清洗不可见字符。
- 分块：使用 `DocumentSplitters.recursive(size, overlap)`（**LangChain4j 0.35 唯一正确写法**），最大字符 400，重叠 50。
  - ⚠️ **严禁写 `DocumentByParagraphSplitter` / `DocumentBySentenceSplitter`**：那是 0.29 之前的类名，0.35 已移除，写了直接编译失败。
- 注入元数据：必须写入 **`courseId`（用于租户隔离）、`docId`、`fileName`、`chunkIndex`**。
  - ⚠️ **必须是 camelCase**。严禁写成 `course_id` / `doc_id` / `file_name` 等 snake_case——键名与检索时 `new IsEqualTo("courseId", ...)` 不一致会导致**过滤静默失效**（表现为：换了课程仍能搜到别的课的内容，且无任何报错）。

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final RagConfigProperties ragProperties;

    public int processAndEmbedDocument(InputStream inputStream, Long courseId, Long docId, String fileName) {
        // 1. Tika 提取文档
        Document document = new ApacheTikaDocumentParser().parse(inputStream);
        
        // 2. 递归切片器配置
        DocumentSplitter splitter = DocumentSplitters.recursive(
                ragProperties.getChunk().getSize(),
                ragProperties.getChunk().getOverlap()
        );
        List<TextSegment> segments = splitter.split(document);

        // 3. 元数据增强
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            Metadata metadata = segment.metadata();
            metadata.put("courseId", String.valueOf(courseId));
            metadata.put("docId", String.valueOf(docId));
            metadata.put("fileName", fileName);
            metadata.put("chunkIndex", i);
        }

        // 4. 向量化并批量写入 Chroma
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);

        log.info("课件 [{}] 向量化完成，共切分 [{}] 个片段", fileName, segments.size());
        return segments.size();
    }

    /**
     * 级联清理：删除/重建课件时必须同步删除 Chroma 中的向量切块，防止"幽灵参考资料"。
     * 成员 B 的课件删除接口与 reindex 接口必须先调用本方法。
     */
    public void removeDocumentVectors(Long docId) {
        embeddingStore.removeAll(new IsEqualTo("docId", String.valueOf(docId)));
        log.info("课件向量切块已级联清理, docId={}", docId);
    }
}
```

### 4.2 严格防幻觉 System Prompt 模板 (`PromptConstants.java`)
```java
public interface PromptConstants {

    String RAG_SYSTEM_PROMPT = """
        你是一名严谨、专业的计算机学科课程助教。请严格依据【课程参考资料】中的内容，回答学生的【提问】。
        
        【课程参考资料】：
        %s
        
        【回答守则与纪律】：
        1. 必须完全忠实于上述资料。若资料中提及相关概念，请给出条理清晰、层次分明的专业解答。
        2. 严禁凭空捏造事实、虚构方法或混淆不同课程的概念。
        3. 若参考资料中并未提及该问题的相关内容，你必须首句明确回答：“在当前课程的已上传课件中，未检索到相关直接说明。”并可给出标准学术定义作为课外补充。
        4. 请使用规范 Markdown 语法组织回复，涉及代码请注明编程语言并添加注释。
        """;

    String KNOWLEDGE_SUMMARY_PROMPT = """
        请针对课程《%s》中的核心知识点【%s】，生成深度解析。
        参考资料：
        %s
        
        请严格按如下 Markdown 结构输出：
        ## 一、核心概念定义与原理
        ## 二、核心难点深度辨析与常见陷阱
        """;
}
```

### 4.3 SSE 核心流式答疑服务 (`SseStreamService.java`)
接口路径：`GET /api/qa/chat/stream?courseId=1&sessionId=101&question=什么是虚拟内存`

**4 阶段 Event 规范**（字段格式以 `DEV_SPECIFICATION.md` 4.2 为唯一标准，所有 data 均为 JSON）：
1. `event: references`：检索到的 Top-K 课件片段列表（出处高亮），字段：`docId`、`fileName`、`chunkIndex`、`score`、`snippet`。
2. `event: message`：大模型流式吐字片段，载荷固定为 `{"delta": "..."}`。
3. `event: done`：完成信号，**必须携带 `recordId` 与 `sessionId`**（供前端点赞/点踩使用）。
4. `event: error`：异常信号，载荷为 `{"errorCode": ..., "message": "..."}`。

**强制前置规则**：
- **会话懒创建**：`sessionId` 为 `0`/空时后端自动插入 `qa_session`（标题取问题前 15 字符），并在 `done` 包回传真实 `sessionId`。
- **专用线程池**：严禁 `CompletableFuture.runAsync` 使用默认公共线程池，必须注入 `sseExecutor`（见 AGENT_INSTRUCTIONS 1.3）。

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class SseStreamService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final StreamingChatLanguageModel streamingChatModel;
    private final RagConfigProperties ragProperties;
    private final QaRecordService qaRecordService;   // 成员 B 提供：流式问答结果落库
    private final QaSessionService qaSessionService; // 成员 B 提供：会话懒创建
    @Resource(name = "sseExecutor")
    private Executor sseExecutor;                    // 专用 SSE 线程池，禁用默认公共池（@Resource 按名注入，避免 Lombok 构造器丢失 @Qualifier）

    public SseEmitter streamChat(Long courseId, Long sessionId, String question) {
        // 设置超时时间 120 秒
        SseEmitter emitter = new SseEmitter(120_000L);

        CompletableFuture.runAsync(() -> {
            try {
                // 0. 会话懒创建：sessionId 为空或 0 时自动新建
                if (sessionId == null || sessionId == 0L) {
                    sessionId = qaSessionService.createSessionLazy(courseId, question);
                }

                final Long finalSessionId = sessionId;

                // 1. 向量检索 (带 courseId 隔离与相似度阈值)
                Embedding queryEmbedding = embeddingModel.embed(question).content();
                Filter courseFilter = new IsEqualTo("courseId", String.valueOf(courseId));

                EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .filter(courseFilter)
                        .maxResults(ragProperties.getChunk().getTopK())
                        .minScore(ragProperties.getChunk().getSimilarityThreshold())
                        .build();

                EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
                List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

                // 2. 发送首包: references (参考出处)
                List<SseReferenceVO> references = matches.stream().map(m -> SseReferenceVO.builder()
                        .docId(Long.valueOf(m.embedded().metadata().getString("docId")))
                        .fileName(m.embedded().metadata().getString("fileName"))
                        .chunkIndex(m.embedded().metadata().getInteger("chunkIndex"))
                        .score(BigDecimal.valueOf(m.score()).setScale(3, RoundingMode.HALF_UP).doubleValue())
                        .snippet(m.embedded().text())
                        .build()
                ).toList();

                emitter.send(SseEmitter.event().name("references").data(references));

                // 3. 组装 Prompt
                String contextText = matches.isEmpty() 
                        ? "暂无匹配课件参考资料" 
                        : matches.stream().map(m -> "【资料出处: " + m.embedded().metadata().getString("fileName") + "】\n" + m.embedded().text())
                                  .collect(Collectors.joining("\n\n"));

                String prompt = String.format(PromptConstants.RAG_SYSTEM_PROMPT, contextText);

                // 4. 调用流式 LLM
                StringBuilder fullAnswer = new StringBuilder();
                long startMillis = System.currentTimeMillis();
                streamingChatModel.generate(
                        List.of(SystemMessage.from(prompt), UserMessage.from(question)),
                        new StreamingResponseHandler<AiMessage>() {
                            @Override
                            public void onNext(String token) {
                                try {
                                    fullAnswer.append(token);
                                    // 统一 JSON 载荷 {"delta": ...}，防止裸 token 含换行破坏 SSE 帧
                                    emitter.send(SseEmitter.event().name("message").data(Map.of("delta", token)));
                                } catch (IOException e) {
                                    log.warn("SSE 发送中断: {}", e.getMessage());
                                }
                            }

                            @Override
                            public void onComplete(Response<AiMessage> response) {
                                try {
                                    // 先落库拿到 recordId（成员 B 的持久化方法），done 包必须回传
                                    long latencyMs = System.currentTimeMillis() - startMillis;
                                    Long recordId = qaRecordService.saveStreamingRecord(
                                            courseId, finalSessionId, question,
                                            fullAnswer.toString(), references, latencyMs);
                                    emitter.send(SseEmitter.event().name("done").data(Map.of(
                                            "recordId", recordId,
                                            "sessionId", finalSessionId,
                                            "finishReason", "stop",
                                            "totalTokens", response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : 0
                                    )));
                                    emitter.complete();
                                } catch (IOException e) {
                                    emitter.completeWithError(e);
                                }
                            }

                            @Override
                            public void onError(Throwable error) {
                                log.error("LLM 推流异常: ", error);
                                try {
                                    emitter.send(SseEmitter.event().name("error").data(Map.of(
                                            "errorCode", 5001, "message", "模型生成中断，请稍后重试")));
                                } catch (Exception ignored) {}
                                emitter.completeWithError(error);
                            }
                        }
                );

            } catch (Exception ex) {
                log.error("RAG 流式问答失败: ", ex);
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of(
                            "errorCode", 5000, "message", "服务暂时不可用，请稍后重试")));
                } catch (Exception ignored) {}
                emitter.completeWithError(ex);
            }
        }, sseExecutor); // 关键：显式传入专用线程池

        return emitter;
    }
}
```

---

## 五、 协同契约与交付物清单

### 5.1 与组内成员的对接要求
1. **与成员 B（后端业务）对接**：
   - 课件切块完成后，回调成员 B 的 `CourseDocumentService.updateParseStatus(docId, CHUNKED, chunkCount)` 更新数据库状态。
   - 问答结束时，通知成员 B 异步持久化 `qa_record` 表（提问内容、AI 回复、命中切块 ID、耗时）。
2. **与成员 C（学生前端）对接**：
   - 严格保证 SSE 4 种事件类型的下发顺序，不能跳步。
   - 跨域支持：必须配置 `CorsRegistry` 允许 `GET /api/qa/chat/stream`，且不能启用分块缓存。
3. **与成员 D（教师前端）对接**：
   - 上传课件后，提供查询该课件已切分片段的接口，用于教师后台切块预览。

### 5.2 成员 A 验收与交付物自测表
- [ ] 本地启动 Chroma 容器，执行切块入库无报错，可在 Chroma 管理端看到 `courseId` 标签。
- [ ] 验证跨课程隔离：在课程 1 提问，绝不召回课程 2 的切块内容。
- [ ] 验证无参考资料提问：提问不相关的政治或娱乐话题，模型触发“未找到相关说明”纪律。
- [ ] 在 Chrome 开发者工具 Network 中检查 `/api/qa/chat/stream`，`Content-Type: text/event-stream` 正常，流式打印无卡顿。
