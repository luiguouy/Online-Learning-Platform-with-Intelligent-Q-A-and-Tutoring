package com.smartqa.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 与大模型配置属性绑定类
 * 对应 application.yml 中 rag.* 节点
 */
@Component
@ConfigurationProperties(prefix = "rag")
public class RagConfigProperties {

    /** 对话大模型配置 */
    private Llm llm = new Llm();

    /** Chroma 向量数据库配置 */
    private Chroma chroma = new Chroma();

    /** 文档分块配置 */
    private Chunk chunk = new Chunk();

    /** 文档解析安全上限配置 */
    private Ingest ingest = new Ingest();

    // ======================== 内部静态类 ========================

    public static class Llm {
        /** 大模型 OpenAI 兼容端点（DeepSeek 或 通义千问 dashscope） */
        private String baseUrl;
        /** API Key（通过环境变量注入，禁止明文提交） */
        private String apiKey;
        /** 模型名称，如 deepseek-chat 或 qwen-plus */
        private String chatModel;
        /** 采样温度，控制生成随机性 */
        private Double temperature = 0.2;
        /**
         * 单次最大生成 Token 数。
         * 【#83 选项②】1500→4000：思考模式下 reasoning 与正文共享本预算，旧值实测被吃满致空正文；
         * 与 application.yml / templates 两处保持一致，改动须三处同改。
         */
        private Integer maxTokens = 4000;
        /**
         * 请求超时（秒）。【#83】60→85：预算抬高后生成时长上升，且必须 < 学生端知识点请求
         * 90s 硬超时（#42）；与 yml/模板两处保持一致。
         */
        private Integer timeoutSeconds = 85;
        /**
         * 【Issue #98】流式问答的「首字节」预算（秒），0 = 关闭看门狗。
         *
         * <p>为什么不是 {@code timeoutSeconds} 就够了：那个值落到 okhttp 是
         * <b>read-timeout（两次读取之间）</b>，上游「排队但一直有字节」时永不触发，
         * 于是 onError/onComplete 都得不到调用，学生只能干等到 SseEmitter 超时。
         * 本值是整体上限：到点仍没收到第一个 token 就主动下发 error 事件结束本轮。</p>
         *
         * <p>默认 45 取在“思考模式起步慢但合理”与“学生不该干等转圈超过 45s”之间，
         * 且必须小于 {@code sseTimeoutSeconds}（否则看门狗没机会先跑）。
         * 与 application.yml / templates 两处保持一致，改动须三处同改。</p>
         */
        private Integer firstTokenTimeoutSeconds = 45;
        /**
         * 【Issue #98】SseEmitter 的整体超时（秒），取代旧代码里写死的 120_000L。
         * 必须同时大于 {@code timeoutSeconds}（给正常长回答留足够余量）与 {@code firstTokenTimeoutSeconds}。
         */
        private Integer sseTimeoutSeconds = 120;

        // Getter / Setter
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getChatModel() { return chatModel; }
        public void setChatModel(String chatModel) { this.chatModel = chatModel; }

        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }

        public Integer getMaxTokens() { return maxTokens; }
        public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public Integer getFirstTokenTimeoutSeconds() { return firstTokenTimeoutSeconds; }
        public void setFirstTokenTimeoutSeconds(Integer firstTokenTimeoutSeconds) {
            this.firstTokenTimeoutSeconds = firstTokenTimeoutSeconds;
        }

        public Integer getSseTimeoutSeconds() { return sseTimeoutSeconds; }
        public void setSseTimeoutSeconds(Integer sseTimeoutSeconds) {
            this.sseTimeoutSeconds = sseTimeoutSeconds;
        }
    }

    public static class Chroma {
        /** Chroma HTTP 服务地址，默认 http://localhost:8000 */
        private String baseUrl;
        /** 向量集合名称 */
        private String collectionName;
        /**
         * 【#65 迁移通道】已存在 collection 的 UUID，仅当本地 uuid 缓存缺失且服务端存在
         * 同名 collection 时兼作手工注入（0.6.3 无法按名反查 id）；常规场景留空，
         * 由 ChromaThinEmbeddingStore 自动创建并缓存。
         */
        private String collectionId;

        // Getter / Setter
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getCollectionName() { return collectionName; }
        public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

        public String getCollectionId() { return collectionId; }
        public void setCollectionId(String collectionId) { this.collectionId = collectionId; }
    }

    public static class Chunk {
        /** 每块字符数 */
        private Integer size = 400;
        /** 块间重叠字符数 */
        private Integer overlap = 50;
        /** 向量检索最低相似度阈值 */
        private Double similarityThreshold = 0.70;
        /** 检索返回 Top-K 块数 */
        private Integer topK = 4;

        // Getter / Setter
        public Integer getSize() { return size; }
        public void setSize(Integer size) { this.size = size; }

        public Integer getOverlap() { return overlap; }
        public void setOverlap(Integer overlap) { this.overlap = overlap; }

        public Double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(Double similarityThreshold) { this.similarityThreshold = similarityThreshold; }

        public Integer getTopK() { return topK; }
        public void setTopK(Integer topK) { this.topK = topK; }
    }

    public static class Ingest {
        /** 单文件解析输入字节上限（防止解压炸弹撑爆内存，第一道限制是 multipart 的 50MB） */
        private Long maxFileBytes = 20L * 1024 * 1024;
        /** 解析提取文本字符上限（Tika WriteLimitReachedException 触发点） */
        private Integer maxTextChars = 500_000;
        /** 单文件解析超时（秒），防止畸形文档卡死切块线程 */
        private Integer parseTimeoutSeconds = 120;

        public Long getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(Long maxFileBytes) { this.maxFileBytes = maxFileBytes; }

        public Integer getMaxTextChars() { return maxTextChars; }
        public void setMaxTextChars(Integer maxTextChars) { this.maxTextChars = maxTextChars; }

        public Integer getParseTimeoutSeconds() { return parseTimeoutSeconds; }
        public void setParseTimeoutSeconds(Integer parseTimeoutSeconds) { this.parseTimeoutSeconds = parseTimeoutSeconds; }
    }

    // ======================== 顶层 Getter / Setter ========================

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public Chroma getChroma() { return chroma; }
    public void setChroma(Chroma chroma) { this.chroma = chroma; }

    public Chunk getChunk() { return chunk; }
    public void setChunk(Chunk chunk) { this.chunk = chunk; }

    public Ingest getIngest() { return ingest; }
    public void setIngest(Ingest ingest) { this.ingest = ingest; }
}
