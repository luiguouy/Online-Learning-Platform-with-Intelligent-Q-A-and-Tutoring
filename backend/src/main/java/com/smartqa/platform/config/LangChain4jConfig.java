package com.smartqa.platform.config;

import com.smartqa.platform.service.rag.ChromaThinEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhq.BgeSmallZhQuantizedEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LangChain4j 核心 Bean 装配配置
 *
 * 路线二：
 *   - 向量模型：本地 BGE-Small-ZH 量化版（零 Token 费用，纯 CPU 计算）
 *   - 对话大模型：OpenAI 兼容端点（DeepSeek / 通义千问均可）
 *   - 向量存储：优先尝试 Chroma；若未配置则降级为内存存储（开发调试用）
 */
@Configuration
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    // ======================== 向量模型（本地，无需网络） ========================

    /**
     * 本地 BGE-Small-ZH 量化向量模型
     * 模型文件约 45 MB，首次调用时从 jar 内自动加载，后续均为内存复用
     * 输出维度：512
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("[RAG] 初始化本地 BGE-Small-ZH-Quantized 向量模型（零 Token 费用）");
        return new BgeSmallZhQuantizedEmbeddingModel();
    }

    // ======================== 对话大模型（阻塞式，用于非流式场景） ========================

    /**
     * 阻塞式 ChatLanguageModel：用于 RAG 管道内部的文档摘要、关键词抽取等
     * 不对外暴露 SSE，响应完整后一次性返回
     */
    @Bean
    @Primary
    public ChatLanguageModel chatLanguageModel(RagConfigProperties props) {
        RagConfigProperties.Llm llm = props.getLlm();
        log.info("[RAG] 初始化 ChatLanguageModel -> model={}, baseUrl={}",
                llm.getChatModel(), llm.getBaseUrl());

        OpenAiChatModel.OpenAiChatModelBuilder chatBuilder = OpenAiChatModel.builder()
                .baseUrl(llm.getBaseUrl())
                .apiKey(llm.getApiKey())
                .modelName(llm.getChatModel())
                .temperature(llm.getTemperature())
                .maxTokens(llm.getMaxTokens())
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                // OpenAI SDK 默认开启 logprobs 等校验，DeepSeek/通义兼容模式需关闭严格校验
                .strictTools(false);
        Proxy chatProxy = buildProxy(llm.getProxy());
        if (chatProxy != null) {
            chatBuilder.proxy(chatProxy);
        }
        return chatBuilder.build();
    }

    // ======================== 流式对话大模型（SSE 问答） ========================

    /**
     * 流式 StreamingChatLanguageModel：用于 SSE 实时流式问答
     * 与阻塞式模型共享同一 API Key 和端点，仅流式参数不同
     */
    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel(RagConfigProperties props) {
        RagConfigProperties.Llm llm = props.getLlm();
        log.info("[RAG] 初始化 StreamingChatLanguageModel -> model={}", llm.getChatModel());

        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder streamBuilder = OpenAiStreamingChatModel.builder()
                .baseUrl(llm.getBaseUrl())
                .apiKey(llm.getApiKey())
                .modelName(llm.getChatModel())
                .temperature(llm.getTemperature())
                .maxTokens(llm.getMaxTokens())
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()));
        Proxy streamProxy = buildProxy(llm.getProxy());
        if (streamProxy != null) {
            streamBuilder.proxy(streamProxy);
        }
        return streamBuilder.build();
    }

    /**
     * 解析可选 HTTP 代理（{@code rag.llm.proxy} / 环境变量 {@code AI_PROXY}）。
     *
     * <p>Java 不读取系统代理设置，访问境外端点（如 OpenRouter）时必须显式注入，
     * 否则会以直连超时或 TLS 握手被中断的形式失败。留空则返回 {@code null}，恢复直连。</p>
     *
     * <p><b>可达性探测</b>：代理客户端（Clash 等）未启动时，硬编码代理会让每次提问都以
     * {@code ConnectException: Connection refused} 失败并回落到 5001「大模型调用异常」，
     * 排查成本很高。故启动时先做一次 TCP 探测（500ms），不可达则改用直连并打 WARN，
     * 使「代理没开」与「模型本身出问题」在日志上可区分。探测只在启动时执行一次，
     * 中途启动代理需重启后端。</p>
     */
    private static Proxy buildProxy(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return null;
        }
        URI uri = URI.create(proxyUrl.trim());
        int port = uri.getPort() > 0 ? uri.getPort() : 7890;
        String host = uri.getHost();
        if (!isReachable(host, port)) {
            log.warn("[RAG] 配置的 HTTP 代理 {}:{} 不可达，本次改为直连（请确认代理客户端已启动；"
                    + "如已中途启动，需重启后端）", host, port);
            return null;
        }
        log.info("[RAG] 大模型请求走 HTTP 代理 -> {}:{}", host, port);
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    /** 轻量 TCP 可达性探测，仅用于启动期判断代理客户端是否在监听。 */
    private static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== 向量存储（Chroma 可达则用之，否则自动降级 InMemory） ========================

    /**
     * 向量存储装配策略：
     *   1. 若配置 rag.chroma.base-url = DISABLED，直接使用 InMemoryEmbeddingStore（跳过探测）
     *   2. 否则对 Chroma 发起 HTTP 健康探测（/api/v2/heartbeat，超时 3 秒）
     *      - 探测成功 → ChromaThinEmbeddingStore（自建薄实现，见下）
     *      - 探测失败 → 降级 InMemoryEmbeddingStore（本地开发免装 Chroma 也能联调）
     *
     * <p><b>【Issue #65 根治】</b>不再用 langchain4j-chroma 0.35 的 ChromaEmbeddingStore：
     * 它构造期无条件 createCollection，collection 已存在即 409 → 后端二次启动必挂
     * （升级 0.36.2 已被 spike 实测否证：存在性检查端点在 chromadb 0.6.3 上因 coroutine bug 恒 400）。
     * ChromaThinEmbeddingStore 用「uuid 本地缓存 + PUT 探测复用」解决重启，语义对齐见其类 Javadoc。</p>
     *
     * <p>注意：InMemory 数据仅存活于进程内存，重启即丢失，生产环境必须启动 Chroma。</p>
     */
    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public EmbeddingStore<TextSegment> embeddingStore(RagConfigProperties props) {
        RagConfigProperties.Chroma chroma = props.getChroma();
        String baseUrl = chroma.getBaseUrl();

        if ("DISABLED".equalsIgnoreCase(baseUrl)) {
            log.warn("[RAG] Chroma 已显式禁用 (rag.chroma.base-url=DISABLED)，使用 InMemoryEmbeddingStore");
            return new InMemoryEmbeddingStore<>();
        }

        if (isChromaReachable(baseUrl)) {
            log.info("[RAG] Chroma 在线，连接向量数据库 -> url={}, collection={}",
                    baseUrl, chroma.getCollectionName());
            // 【#65】构造期即解析 collection（复用/创建/迁移注入），失败抛带可行动指引的启动错误
            return ChromaThinEmbeddingStore.builder()
                    .baseUrl(baseUrl)
                    .collectionName(chroma.getCollectionName())
                    .collectionId(chroma.getCollectionId())
                    .build();
        }

        log.warn("[RAG] Chroma 不可达 ({})，自动降级为 InMemoryEmbeddingStore（仅限本地开发调试，重启后数据丢失！）",
                baseUrl);
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 探测 Chroma 服务是否存活：GET {baseUrl}/api/v2/heartbeat，3 秒超时。
     * 任何网络异常 / 非 2xx 响应均视为不可达。
     */
    private boolean isChromaReachable(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/api/v2/heartbeat"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            log.info("[RAG] Chroma 心跳探测失败: {} ({})", baseUrl, e.getMessage());
            return false;
        }
    }
}
