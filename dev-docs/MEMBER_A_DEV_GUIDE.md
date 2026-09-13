# 成员 A 详细开发文档：AI 与 RAG 核心算法工程师

> **角色**：成员 A（AI / RAG 核心算法工程师 · 后端组长）  
> **职责模块**：Spring Boot 工程底座、LangChain4j 接入、课件解析与向量化存储、RAG 检索增强、SSE 流式智能答疑接口、知识点结构化生成  
> **适用技术栈**：Spring Boot 3.3.5（锁定）+ LangChain4j 0.35.0（锁定）+ Chroma DB / 本地向量库 + 阿里云百炼/DeepSeek API + SSE (Server-Sent Events)

---

## 一、 模块定位与工程职责边界

成员 A 是整个系统的**“智能大脑枢纽”**，主要负责打通从**课件非结构化数据输入**到**高质量精准流式输出**的核心链路：

1. **工程骨架与基础脚手架**：初始化 Spring Boot 3.x 统一工程，配置 Maven 依赖管理。
2. **文档解析与切块（Document Ingestion）**：支持 PDF、Markdown、TXT 格式课件解析，执行递归字符切片（Chunk Size = 400，Overlap = 50）。
3. **向量化与存储（Embedding & Vector Store）**：使用本地轻量向量模型（BGE-small 纯 CPU 离线计算，零成本），元数据（courseId、docId、fileName）关联注入，存入 Chroma 或内置向量库。
4. **多阶段检索与防幻觉调优**：按 `courseId` 租户级过滤，Cosine 相似度 `>= 0.70` 过滤，召回 Top-K（3~4），组装严谨防幻觉 System Prompt。
5. **SSE 流式智能答疑接口**：对外提供 `GET /api/qa/chat/stream`，按照 4 阶段协议（`references` -> `message` -> `done` -> `error`）向前端打字机推流。
6. **知识点解析**：提供 `POST /api/knowledge/generate` 接口，返回结构化 Markdown 精解（核心概念定义 + 难点辨析），**不生成自测题**。
   - ⚠️ **必须加 Sa-Token 登录校验**（`StpUtil.checkLogin()`）。该接口单次调用消耗大量 Token，不鉴权会被恶意刷爆额度——这也是评委常问的"成本如何控制"。
   - ⚠️ 同时必须做**按用户限流**（实现见 `MEMBER_B_DEV_GUIDE.md` 4.6）。

---

## 二、 Maven 依赖与核心配置

### 2.1 `pom.xml` 核心依赖清单

> ⚠️ **本清单只是 `pom.xml` 的一部分**。成员 B 负责的依赖（MyBatis-Plus、MySQL 驱动、Sa-Token、Knife4j、spring-security-crypto）列在 `MEMBER_B_DEV_GUIDE.md` 3.1 节。
> 本项目是**单体 Spring Boot 工程**（见 `AGENT_INSTRUCTIONS.md` 零章禁令），**两份清单必须合并进同一个 `pom.xml`**——不要创建两个工程、两个 pom。缺任何一半，编译都会失败。

**`<parent>` 与属性必须锁定为以下版本**（v7.0 查证后锁定，禁止让 AI 自由选版本）：
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
</parent>

<properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>
```
> **为什么锁 3.3.5**：本项目其余依赖均发布于 2024 年上半年（LangChain4j 0.35.0、Sa-Token 1.38.0、MyBatis-Plus 3.5.7、Knife4j 4.5.0），它们都在 Spring Boot 3.2/3.3 时代验证过。若让 AI 自由选"最新的 3.x"（如 3.5.x），可能撞上 3.4+ 的 deprecated API 移除——零经验团队没有排查这种问题的能力。**用 Spring Initializr 建工程时也要把版本改回 3.3.5**，不要直接用它给的最新版。
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
    <!-- 本地轻量量化向量模型 (BGE-Small-ZH, 纯本地CPU运行, 零Token成本) -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-embeddings-bge-small-zh-q</artifactId>
        <version>0.35.0</version>
    </dependency>
    <!-- Apache Tika (文档智能解析；底层虽支持 DOCX 等格式，但上传白名单只有 PDF/Markdown/TXT，见本指南第一章第 2 条) -->
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

> ⚠️ **本段只包含 RAG 相关配置，不是完整文件**。完整可用的 `application.yml` 见 `AGENT_INSTRUCTIONS.md` 1.2 节（还含 `spring.datasource`、`sa-token`、`file.upload-dir` 等必需项）。
> 只按本段配置启动会直接失败：成员 B 的 `@Value("${file.upload-dir}")` 会报 `Could not resolve placeholder 'file.upload-dir'`。
```yaml
server:
  port: 8080
  servlet:
    context-path: /

rag:
  llm:
    base-url: https://api.deepseek.com/v1 # 兼容 OpenAI 格式，支持 DeepSeek 官方或阿里云百炼
    api-key: ${AI_API_KEY:sk-placeholder} # 生产使用环境变量注入
    chat-model: deepseek-chat # 或 qwen-plus
    temperature: 0.2
    max-tokens: 1500
    timeout-seconds: 60
  # 向量模型说明：已采用内置 BGE-Small-ZH 本地量化模型 (纯本地CPU计算，零Token费用，无远程接口依赖)
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

### 2.3 RAG 配置映射类 (`RagConfigProperties.java`)

上面 yml 里的每个键都必须有对应的 Java 字段（**驼峰映射**），否则 `ragProperties.getChunk().getSize()` 这类调用会编译失败。**本类必须按下面这样写，字段名不要改**：

```java
@Data
@Component
@ConfigurationProperties(prefix = "rag")
public class RagConfigProperties {

    private Llm llm = new Llm();
    private Chroma chroma = new Chroma();
    private Chunk chunk = new Chunk();

    @Data
    public static class Llm {
        private String baseUrl;          // rag.llm.base-url
        private String apiKey;           // rag.llm.api-key
        private String chatModel;        // rag.llm.chat-model
        private String embeddingModel;   // rag.llm.embedding-model (历史字段，路线二已采用内置 BgeSmallZhQuantizedEmbeddingModel)
        private Double temperature;      // rag.llm.temperature
        private Integer maxTokens;       // rag.llm.max-tokens
        private Integer timeoutSeconds;  // rag.llm.timeout-seconds
    }

    @Data
    public static class Chroma {
        private String baseUrl;          // rag.chroma.base-url
        private String collectionName;   // rag.chroma.collection-name
    }

    @Data
    public static class Chunk {
        private Integer size;                // rag.chunk.size
        private Integer overlap;             // rag.chunk.overlap
        private Double similarityThreshold;  // rag.chunk.similarity-threshold
        private Integer topK;                // rag.chunk.top-k
    }
}
```

> ⚠️ 两个易错点：① `top-k` 映射到 `topK`（不是 `topk`）；② `similarity-threshold` 映射到 `similarityThreshold`（不是 `similarity`）。字段名写错不会报错，只会**静默取到 null**，运行时才暴露。

### 2.4 模型与向量库 Bean 装配 (`LangChain4jConfig.java`) —— 必写，否则启动失败

**为什么必须手写**：本项目的配置前缀是自定义的 `rag.llm.*`，而 `langchain4j-spring-boot-starter` 的自动配置只认 `langchain4j.open-ai.*`。两者对不上，**不会自动创建 Bean**。而 `SseStreamService` 要注入 `StreamingChatLanguageModel`、`DocumentIngestionService` 要注入 `EmbeddingModel` 与 `EmbeddingStore<TextSegment>`——**缺 Bean 会在启动时直接报 `NoSuchBeanDefinitionException`**。

```java
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private final RagConfigProperties rag;

    /** 流式对话模型：OpenAI 兼容协议，可直连通义千问 / DeepSeek */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(rag.getLlm().getBaseUrl())
                .apiKey(rag.getLlm().getApiKey())
                .modelName(rag.getLlm().getChatModel())        // 注意：用 chatModel
                .temperature(rag.getLlm().getTemperature())
                .maxTokens(rag.getLlm().getMaxTokens())
                .timeout(Duration.ofSeconds(rag.getLlm().getTimeoutSeconds()))
                .build();
    }

    /** 向量化模型：采用本地 BGE-Small-ZH 量化模型，纯本地 CPU 毫秒级运算，永久 0 成本，无网络依赖 */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhQuantizedEmbeddingModel();
    }

    /** 向量库：Chroma（collection 名固定 smart_qa_course_docs） */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return ChromaEmbeddingStore.builder()
                .baseUrl(rag.getChroma().getBaseUrl())
                .collectionName(rag.getChroma().getCollectionName())
                .build();
    }
}
```

> **验收标准**：启动日志无 `NoSuchBeanDefinitionException`；访问 Knife4j 正常。若报找不到 Bean，先检查本类是否被 Spring 扫到（包路径必须在 `com.smartqa.platform` 下）。

### 2.5 关键 VO 定义 (`SseReferenceVO.java`) —— 注解必须齐全

`SseReferenceVO` 既是 SSE `references` 事件的载荷，也是 `qa_record.grounding_references` 这个 JSON 字段的反序列化目标。**Jackson 反序列化要求无参构造 + setter**，所以下面四个注解一个都不能少（**只写 `@Builder` 会导致从数据库读记录时反序列化失败**）：

```java
@Data
@Builder
@NoArgsConstructor      // Jackson 反序列化必需
@AllArgsConstructor     // 配合 @Builder 必需
public class SseReferenceVO {
    private Long docId;          // 课件ID
    private String fileName;     // 课件文件名
    private Integer chunkIndex;  // 命中片段序号
    private Double score;        // 相似度 0~1
    private String snippet;      // 片段原文
}
```

> **字段名与前端严格一致**：`docId` / `fileName` / `chunkIndex` / `score` / `snippet` —— 前端 `SseReference` 接口按此定义（见 `MEMBER_C_DEV_GUIDE.md` 4.1），改一个字段就要同步改前端。

---

## 三、 模块代码结构与详细类设计

```text
com.smartqa.platform                  ← 与 AGENT_INSTRUCTIONS 1.1 完全一致，禁止另起 rag.* 顶层包
├── config/
│   ├── RagConfigProperties.java        // RAG 参数映射 (@ConfigurationProperties(prefix = "rag"))
│   └── LangChain4jConfig.java          // LLM / EmbeddingModel / EmbeddingStore Bean 装配
├── controller/
│   ├── SseChatController.java          // /api/qa/chat/stream 控制器
│   └── KnowledgeController.java        // /api/knowledge/generate 控制器
├── service/rag/
│   ├── DocumentIngestionService.java   // 课件解析、切片、向量化入库
│   ├── RagRetrievalService.java        // 向量相似度检索与上下文装配
│   └── SseStreamService.java           // SSE 流式推送控制
├── constant/
│   └── PromptConstants.java            // 防幻觉提示词模板
└── model/
    ├── dto/KnowledgeGenerateDTO.java   // 知识点解析请求 DTO
    └── vo/
        ├── SseReferenceVO.java         // 检索溯源首包 VO
        └── KnowledgeGenerateVO.java    // 知识点解析响应 VO
```

> ⚠️ **包结构必须与 `AGENT_INSTRUCTIONS.md` 1.1 保持一致**（那是全项目的包规范）。
> 特别注意：Controller 一律放顶层 `controller/`，RAG 业务服务放 `service/rag/`，**不要**自建 `rag.controller` / `rag.service` / `rag.model` 这类子包——否则成员 A 与成员 B 写出的类会分散在两套目录下，合并后 import 全乱。

---

## 四、 核心业务逻辑实现指南

### 4.1 文档切块与元数据注入 (`DocumentIngestionService.java`)
**实现要点**：
- 输入：`MultipartFile file`, `Long courseId`, `Long docId`
- 解析：使用 Tika 将 PDF/Markdown 转换为纯净文本并清洗不可见字符。
- 分块：**下面两种写法都是 LangChain4j 的正式 API，任选其一即可，但不要混用**（参数均为「最大片段长度, 重叠长度」）：
  - `DocumentSplitters.recursive(400, 50)` —— 递归多级切分，**本项目推荐**（与下方代码一致）
  - `new DocumentByParagraphSplitter(400, 50)` —— 按段落切分，段落过长时自动降级为句子切分
  两者同属 `dev.langchain4j.data.document.splitter` 包，均长期可用，不存在"已移除"的问题。
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
                // 0. 会话懒创建：sessionId 为空或 0 时自动新建（使用局部变量，避免 Lambda 捕获形参再赋值导致编译错误）
                Long actualSessionId = (sessionId == null || sessionId == 0L)
                        ? qaSessionService.createSessionLazy(courseId, question)
                        : sessionId;

                final Long finalSessionId = actualSessionId;

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
   - 切块完成后**只需返回切片数量**（`processAndEmbedDocument` 返回 `int`）。**状态回写由成员 B 的异步块负责**（见 `MEMBER_B_DEV_GUIDE.md` 4.3 的 `setParseStatus("CHUNKED")` + `updateById`），A **不需要也不应该**自己去更新 `course_document` 表。
   - 问答结束时，调用成员 B 的 `QaRecordService.saveStreamingRecord(...)` 落库（提问内容、AI 回复、命中切块引用、耗时），并把它返回的 `recordId` 放进 SSE `done` 包。
2. **与成员 C（学生前端）对接**：
   - 严格保证 SSE 4 种事件类型的下发顺序，不能跳步。
   - 跨域支持：必须配置 `CorsRegistry` 允许 `GET /api/qa/chat/stream`，且不能启用分块缓存。
3. **与成员 D（教师前端）对接**：
   - 课件切块完成后必须把 `chunkCount` 回写进 `course_document` 表（**由成员 B 的异步块完成**），教师端课件列表会展示"切块数"。
   - **不提供"切块片段明细"接口**——该功能不在本期范围内，教师端只展示切块数量。

### 5.2 成员 A 验收与交付物自测表
- [ ] 本地启动 Chroma 容器，执行切块入库无报错，可在 Chroma 管理端看到 `courseId` 标签。
- [ ] 验证跨课程隔离：在课程 1 提问，绝不召回课程 2 的切块内容。
- [ ] 验证无参考资料提问：提一个**课件里完全没有的专业问题**（例如用《操作系统》的课件回答《计算机网络》的题目），模型必须回答"当前课程课件中未检索到相关内容"，而不是编造答案。
- [ ] 在 Chrome 开发者工具 Network 中检查 `/api/qa/chat/stream`，`Content-Type: text/event-stream` 正常，流式打印无卡顿。
