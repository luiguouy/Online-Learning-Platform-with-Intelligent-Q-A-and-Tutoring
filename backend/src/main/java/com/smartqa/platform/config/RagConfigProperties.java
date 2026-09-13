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
        /** 单次最大生成 Token 数 */
        private Integer maxTokens = 1500;
        /** 请求超时（秒） */
        private Integer timeoutSeconds = 60;

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
    }

    public static class Chroma {
        /** Chroma HTTP 服务地址，默认 http://localhost:8000 */
        private String baseUrl;
        /** 向量集合名称 */
        private String collectionName;

        // Getter / Setter
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getCollectionName() { return collectionName; }
        public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
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

    // ======================== 顶层 Getter / Setter ========================

    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }

    public Chroma getChroma() { return chroma; }
    public void setChroma(Chroma chroma) { this.chroma = chroma; }

    public Chunk getChunk() { return chunk; }
    public void setChunk(Chunk chunk) { this.chunk = chunk; }
}
