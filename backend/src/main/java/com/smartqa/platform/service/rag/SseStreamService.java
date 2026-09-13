package com.smartqa.platform.service.rag;

import com.smartqa.platform.config.RagConfigProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SSE 流式问答核心服务（A1.5）
 *
 * 流程：
 *   1. 将用户问题向量化（本地 BGE-Small-ZH）
 *   2. 在 Chroma 中相似度检索 Top-K 课件片段
 *   3. 拼装 RAG 系统提示词（上下文 + 问题）
 *   4. 调用 StreamingChatLanguageModel，逐 Token 推送到 SseEmitter
 *
 * 线程安全：使用 @Async 在独立线程池中执行，不阻塞 Tomcat 工作线程
 */
@Service
public class SseStreamService {

    private static final Logger log = LoggerFactory.getLogger(SseStreamService.class);

    /** RAG 系统提示词模板 */
    private static final String RAG_SYSTEM_PROMPT = """
            你是一个专业的在线学习智能答疑助手。请根据以下课件内容回答学生的问题。
            
            【课件相关内容】
            %s
            
            【回答要求】
            1. 优先基于上述课件内容进行作答，保持准确性
            2. 若课件内容不足以回答，可以补充学科常识，但必须显式标注【课外补充说明】
            3. 回答简洁清晰，适合学生理解
            4. 使用中文回答
            """;

    private final StreamingChatLanguageModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final QaSessionService qaSessionService;
    private final RagConfigProperties ragProps;

    public SseStreamService(StreamingChatLanguageModel streamingChatModel,
                            EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            QaSessionService qaSessionService,
                            RagConfigProperties ragProps) {
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.qaSessionService = qaSessionService;
        this.ragProps = ragProps;
    }

    /**
     * 执行 RAG 流式问答，将结果逐 Token 推送给 SseEmitter
     *
     * @param emitter    SSE 输出通道（由 Controller 层创建并返回给前端）
     * @param courseId   课程 ID（用于检索过滤与会话绑定）
     * @param question   用户问题
     * @param sessionId  问答会话 ID（为 null 时自动创建新会话）
     */
    @Async("sseExecutor")
    public void streamChat(SseEmitter emitter, Long courseId, String question, Long sessionId) {
        // ── 懒创建会话 ID（关键修复：使用局部变量，避免 Lambda 捕获可变变量） ──
        final Long actualSessionId;
        if (sessionId == null) {
            actualSessionId = qaSessionService.createSessionLazy(courseId, question);
            log.info("[SSE] 新建会话 sessionId={}, courseId={}", actualSessionId, courseId);
        } else {
            actualSessionId = sessionId;
            log.info("[SSE] 复用会话 sessionId={}, courseId={}", actualSessionId, courseId);
        }

        try {
            // ── Step 1：问题向量化 ──────────────────────────────────────────────
            Embedding questionEmbedding = embeddingModel.embed(question).content();

            // ── Step 2：向量检索 Top-K 相关课件片段（courseId 租户级过滤，防跨课程串内容） ──
            RagConfigProperties.Chunk chunkCfg = ragProps.getChunk();
            Filter courseFilter = new IsEqualTo("courseId", String.valueOf(courseId));
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(questionEmbedding)
                    .filter(courseFilter)
                    .maxResults(chunkCfg.getTopK())
                    .minScore(chunkCfg.getSimilarityThreshold())
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);

            String context = searchResult.matches().stream()
                    .map(match -> match.embedded().text())
                    .collect(Collectors.joining("\n\n---\n\n"));

            if (context.isBlank()) {
                context = "（未检索到相关课件内容，请基于学科常识作答并标注【课外补充说明】）";
                log.warn("[SSE] 检索结果为空，sessionId={}, courseId={}", actualSessionId, courseId);
            } else {
                log.info("[SSE] 检索到 {} 个相关片段，sessionId={}", searchResult.matches().size(), actualSessionId);
            }

            // ── Step 3：拼装 RAG 提示词 ─────────────────────────────────────────
            String systemPrompt = String.format(RAG_SYSTEM_PROMPT, context);
            UserMessage userMessage = UserMessage.from(systemPrompt + "\n\n【学生问题】\n" + question);

            // ── Step 4：流式调用大模型，逐 Token 推送 SSE ───────────────────────
            streamingChatModel.generate(List.of(userMessage), new StreamingResponseHandler<AiMessage>() {

                @Override
                public void onNext(String token) {
                    // 每收到一个 Token，立刻推送给前端
                    try {
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(token));
                    } catch (IOException e) {
                        log.warn("[SSE] 客户端已断开，sessionId={}", actualSessionId);
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    // 全部 Token 推送完毕，发送结束事件并关闭
                    try {
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data("[DONE]"));
                        emitter.complete();
                        log.info("[SSE] 流式回答完毕，sessionId={}", actualSessionId);
                    } catch (IOException e) {
                        log.warn("[SSE] 完成事件发送失败，sessionId={}", actualSessionId);
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    log.error("[SSE] 大模型调用异常，sessionId={}", actualSessionId, error);
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data("服务异常，请稍后重试"));
                    } catch (IOException ignored) {
                        // 忽略发送失败
                    }
                    emitter.completeWithError(error);
                }
            });

        } catch (Exception e) {
            log.error("[SSE] streamChat 异常，sessionId={}", actualSessionId, e);
            try {
                emitter.send(SseEmitter.event().name("error").data("服务内部异常: " + e.getMessage()));
            } catch (IOException ignored) {
                // 忽略
            }
            emitter.completeWithError(e);
        }
    }
}
