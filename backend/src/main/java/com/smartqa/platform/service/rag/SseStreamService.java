package com.smartqa.platform.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.config.RagConfigProperties;
import com.smartqa.platform.entity.QaSession;
import com.smartqa.platform.service.QaRecordService;
import com.smartqa.platform.service.QaSessionService;
import com.smartqa.platform.vo.SseReferenceVO;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * SSE 流式问答核心服务（A1.5）
 *
 * 流程：
 *   1. 懒创建 / 校验会话（成员 B 的 qa_session 持久化，非本人会话拒绝）
 *   2. 将用户问题向量化（本地 BGE-Small-ZH）
 *   3. 在 Chroma 中相似度检索 Top-K 课件片段，先下发 event: references 出处包
 *   4. 拼装 RAG 系统提示词，流式调用大模型，逐 Token 下发 event: message
 *   5. 流结束后落库 qa_record（成员 B 的 saveStreamingRecordAs），done 包回传 recordId
 *
 * 事件契约（DEV_SPECIFICATION.md 4.2 冻结，data 载荷一律为 JSON）：
 *   event: references → [{"docId":..,"fileName":..,"chunkIndex":..,"score":..,"snippet":..}]
 *   event: message   → {"delta": "..."}
 *   event: done      → {"recordId":..,"sessionId":..,"finishReason":"stop","totalTokens":..}
 *   event: error     → {"errorCode":..,"message":"..."}
 *
 * 线程安全：@Async 运行在 sseExecutor，Sa-Token ThreadLocal 登录态不可用，
 * 故 userId 由 Controller 在请求线程捕获后显式传入（成员 B 指南 5.1 的约定）。
 */
@Service
public class SseStreamService {

    private static final Logger log = LoggerFactory.getLogger(SseStreamService.class);

    /** done/error 包里的业务错误码（与 4.2 示例口径一致：5001=模型异常，5002=落库失败，5000=内部异常） */
    private static final int ERROR_CODE_LLM = 5001;
    private static final int ERROR_CODE_PERSIST = 5002;
    private static final int ERROR_CODE_INTERNAL = 5000;

    /** 出处包 snippet 截断长度，防止单包过大 */
    private static final int SNIPPET_MAX_CHARS = 200;

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
    private final QaRecordService qaRecordService;
    private final RagConfigProperties ragProps;
    private final ObjectMapper objectMapper;

    public SseStreamService(StreamingChatLanguageModel streamingChatModel,
                            EmbeddingModel embeddingModel,
                            EmbeddingStore<TextSegment> embeddingStore,
                            QaSessionService qaSessionService,
                            QaRecordService qaRecordService,
                            RagConfigProperties ragProps,
                            ObjectMapper objectMapper) {
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.qaSessionService = qaSessionService;
        this.qaRecordService = qaRecordService;
        this.ragProps = ragProps;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 RAG 流式问答，将结果按 4.2 契约逐事件推送给 SseEmitter
     *
     * @param emitter    SSE 输出通道（由 Controller 层创建并返回给前端）
     * @param userId     提问学生 ID（请求线程捕获后传入，本方法运行在 sseExecutor 异步线程）
     * @param courseId   课程 ID（用于检索过滤与会话绑定）
     * @param question   用户问题
     * @param sessionId  问答会话 ID；按契约传 0 或 null 时懒创建新会话
     */
    @Async("sseExecutor")
    public void streamChat(SseEmitter emitter, Long userId, Long courseId, String question, Long sessionId) {
        final long startMillis = System.currentTimeMillis();

        // ── Step 0：会话懒创建 / 归属校验（B 的 qa_session 持久化） ──────────────
        final Long actualSessionId;
        try {
            if (sessionId == null || sessionId <= 0L) {
                actualSessionId = qaSessionService.createSessionLazyForUser(userId, courseId, question);
                log.info("[SSE] 懒创建会话 sessionId={}, courseId={}", actualSessionId, courseId);
            } else {
                QaSession owned = qaSessionService.getOwnedSession(sessionId, userId);
                actualSessionId = owned.getId();
                log.info("[SSE] 复用会话 sessionId={}, courseId={}", actualSessionId, courseId);
            }
        } catch (BusinessException e) {
            log.warn("[SSE] 会话校验失败 userId={}, sessionId={}, msg={}", userId, sessionId, e.getMessage());
            sendErrorQuietly(emitter, e.getCode(), e.getMessage());
            emitter.complete();
            return;
        }

        // 客户端断开标记：断开后停止推流，但回答照常落库，不丢记录
        final AtomicBoolean clientGone = new AtomicBoolean(false);
        final StringBuilder answerBuilder = new StringBuilder();

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
            List<SseReferenceVO> references = toReferences(searchResult.matches());

            String context = searchResult.matches().stream()
                    .map(match -> match.embedded().text())
                    .collect(Collectors.joining("\n\n---\n\n"));

            if (context.isBlank()) {
                context = "（未检索到相关课件内容，请基于学科常识作答并标注【课外补充说明】）";
                log.warn("[SSE] 检索结果为空，sessionId={}, courseId={}", actualSessionId, courseId);
            } else {
                log.info("[SSE] 检索到 {} 个相关片段，sessionId={}", references.size(), actualSessionId);
            }

            // 契约 4.2：references 为流上第一个数据包，先出处后正文
            sendJsonEvent(emitter, clientGone, "references", references);

            // ── Step 3：拼装 RAG 提示词 ─────────────────────────────────────────
            String systemPrompt = String.format(RAG_SYSTEM_PROMPT, context);
            UserMessage userMessage = UserMessage.from(systemPrompt + "\n\n【学生问题】\n" + question);

            // ── Step 4：流式调用大模型，逐 Token 推送 SSE ───────────────────────
            streamingChatModel.generate(List.of(userMessage), new StreamingResponseHandler<AiMessage>() {

                @Override
                public void onNext(String token) {
                    answerBuilder.append(token);
                    sendJsonEvent(emitter, clientGone, "message", Map.of("delta", token));
                }

                @Override
                public void onComplete(Response<AiMessage> response) {
                    // ── Step 5：先落库拿 recordId，再发 done 包（契约要求 done 必含 recordId） ──
                    Long recordId;
                    try {
                        long latencyMs = System.currentTimeMillis() - startMillis;
                        recordId = qaRecordService.saveStreamingRecordAs(
                                userId, courseId, actualSessionId,
                                question, answerBuilder.toString(), references, latencyMs);
                    } catch (Exception e) {
                        log.error("[SSE] 问答记录落库失败，sessionId={}", actualSessionId, e);
                        sendErrorQuietly(emitter, ERROR_CODE_PERSIST, "问答记录保存失败，请稍后重试");
                        emitter.complete();
                        return;
                    }

                    TokenUsage usage = response.tokenUsage();
                    int totalTokens = (usage != null && usage.totalTokenCount() != null)
                            ? usage.totalTokenCount() : 0;
                    Map<String, Object> donePayload = Map.of(
                            "recordId", recordId,
                            "sessionId", actualSessionId,
                            "finishReason", "stop",
                            "totalTokens", totalTokens);
                    sendJsonEvent(emitter, clientGone, "done", donePayload);
                    emitter.complete();
                    log.info("[SSE] 流式回答完毕，recordId={}, sessionId={}, clientGone={}",
                            recordId, actualSessionId, clientGone.get());
                }

                @Override
                public void onError(Throwable error) {
                    log.error("[SSE] 大模型调用异常，sessionId={}", actualSessionId, error);
                    sendErrorQuietly(emitter, ERROR_CODE_LLM, "大模型调用异常，请稍后重试");
                    emitter.complete();
                }
            });

        } catch (Exception e) {
            log.error("[SSE] streamChat 异常，sessionId={}", actualSessionId, e);
            sendErrorQuietly(emitter, ERROR_CODE_INTERNAL, "服务内部异常，请稍后重试");
            emitter.complete();
        }
    }

    /** 检索命中 → 契约 SseReferenceVO 列表（字段名与 4.2 / B 的 groundingReferences 快照一致） */
    private List<SseReferenceVO> toReferences(List<EmbeddingMatch<TextSegment>> matches) {
        List<SseReferenceVO> list = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            Map<String, Object> meta = segment.metadata().toMap();
            list.add(SseReferenceVO.builder()
                    .docId(parseLongOrNull(meta.get("docId")))
                    .fileName(meta.get("fileName") == null ? null : String.valueOf(meta.get("fileName")))
                    .chunkIndex(parseIntOrNull(meta.get("chunkIndex")))
                    .score(match.score())
                    .snippet(abbreviate(segment.text()))
                    .build());
        }
        return list;
    }

    /** 统一 JSON 事件下发：序列化失败/客户端断开都不允许炸掉推流线程 */
    private void sendJsonEvent(SseEmitter emitter, AtomicBoolean clientGone, String eventName, Object payload) {
        if (clientGone.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            log.error("[SSE] 事件 {} 序列化失败", eventName, e);
        } catch (IOException e) {
            // 客户端断开（用户离开页面/abort()）：停止推流，回答照常落库
            log.warn("[SSE] 客户端已断开，停止推流（事件 {}）", eventName);
            clientGone.set(true);
            try {
                emitter.completeWithError(e);
            } catch (IllegalStateException ignored) {
                // emitter 已被回调线程关闭，忽略
            }
        }
    }

    /** error 事件下发，payload 与 4.2 对齐：{"errorCode":..,"message":".."} */
    private void sendErrorQuietly(SseEmitter emitter, int errorCode, String message) {
        sendJsonEvent(emitter, new AtomicBoolean(false), "error",
                Map.of("errorCode", errorCode, "message", message));
    }

    private static String abbreviate(String text) {
        if (text == null || text.length() <= SNIPPET_MAX_CHARS) {
            return text;
        }
        return text.substring(0, SNIPPET_MAX_CHARS) + "...";
    }

    private static Long parseLongOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
