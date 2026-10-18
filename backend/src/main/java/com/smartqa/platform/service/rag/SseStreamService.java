package com.smartqa.platform.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.config.RagConfigProperties;
import com.smartqa.platform.entity.Course;
import com.smartqa.platform.entity.QaRecord;
import com.smartqa.platform.entity.QaSession;
import com.smartqa.platform.service.CourseService;
import com.smartqa.platform.service.QaRecordService;
import com.smartqa.platform.service.QaSessionService;
import org.springframework.util.StringUtils;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * SSE 流式问答核心服务（A1.5）
 *
 * 流程：
 * 1. 懒创建 / 校验会话（成员 B 的 qa_session 持久化，非本人会话拒绝、跨课程会话拒绝 #57）
 * 2. 将用户问题向量化（本地 BGE-Small-ZH）
 * 3. 在 Chroma 中相似度检索 Top-K 课件片段，先下发 event: references 出处包
 * 4. 拼装 RAG 系统提示词，流式调用大模型，逐 Token 下发 event: message
 * 5. 流结束后落库 qa_record（成员 B 的 saveStreamingRecordAs），done 包回传 recordId
 *
 * 事件契约（DEV_SPECIFICATION.md 4.2 冻结，data 载荷一律为 JSON）：
 * event: references →
 * [{"docId":..,"fileName":..,"chunkIndex":..,"score":..,"snippet":..}]
 * event: message → {"delta": "..."}
 * event: done →
 * {"recordId":..,"sessionId":..,"finishReason":"stop","totalTokens":..}
 * event: error → {"errorCode":..,"message":"..."}
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

    /**
     * RAG 系统提示词模板。
     *
     * <p>
     * 【#57 三】第 2 条是契约硬性要求，此前实现缺失：
     * {@code DEV_SPECIFICATION.md} §5.2 第 2 条要求「若参考资料中未提及相关信息，请明确回答
     * “在当前课程课件中未找到该问题的明确说明”」，{@code MEMBER_A_DEV_GUIDE.md} §4.2 第 3 条
     * 进一步要求该声明位于首句。旧提示词只写了「课件不足时可补充学科常识 + 标注
     * 【课外补充说明】」，没有任何「须明确说明未找到」的约束，导致
     * {@code C3-演示问题清单.md} Q5「防幻觉拒答」与 {@code COLLABORATION_WORKFLOW.md}
     * R9 应对措施② 的输出是否符合契约无法保证。
     * </p>
     *
     * <p>
     * 注意：本串经 String.format 填充 context，修改时勿引入裸的百分号字符，
     * 否则会抛 UnknownFormatConversionException。
     * </p>
     */
    private static final String RAG_SYSTEM_PROMPT = """
            你是一个专业的在线学习智能答疑助手与学伴。当前学生正在学习《%s》课程（课程简介：%s）。
            请根据以下课件内容，结合《%s》专业学科知识，深入、严谨、详尽且有条理地解答学生的问题。

            【课件相关内容】
            %s

            【回答要求与纪律】
            1. 课件内容优先：若上述课件内容中包含该问题的相关信息，你必须优先充分依据课件内容进行精准作答，保持学科严肃性，并自然标注出处来源（例如：“根据本课程课件……”）。
            2. 若上述课件内容中未提及该问题的相关信息：
               - 你必须首句明确回答：“在当前课程课件中未找到该问题的明确说明”
               - 紧接着，你【绝对不能拒绝回答、严禁敷衍推脱说无法说明或请联系教师】！你必须立刻显式标注【课外补充说明】，并调动《%s》的标准大学专业课程知识体系与核心原理，针对学生的具体提问给出完整、详尽、具体、结构化的解答！
               - 例如当学生提问课程知识体系或学习主线时，哪怕课件中没有专门总结，也必须把《%s》的核心板块（如进程/线程管理、并发与同步死锁、内存管理与虚存、文件系统与存储、I/O系统等）完整细致地梳理出来。
            3. 若课件内容仅包含部分信息不足以完整解答，可补充学科常识，但必须显式标注【课外补充说明】，不得与课件内容混淆，并确保回答内容充实、富有教学指导价值。
            4. 善用 Markdown 格式排版（标题、加粗、分段、列表），逻辑清晰、层次分明，适合学生学习与掌握。
            5. 全程使用中文回答。
            """;

    /**
     * 启发式教学辅导模式系统提示词模板（苏格拉底式教学法）
     */
    private static final String TUTOR_SYSTEM_PROMPT = """
            你是一个循循善诱、专业的计算机学科启发式辅导导师（Tutor Agent）。当前学生正在学习《%s》课程（课程简介：%s）。
            请根据以下课件内容，结合《%s》专业学科知识，采用【苏格拉底启发式教学法】辅导学生。

            【课件相关内容】
            %s

            【启发式辅导原则】
            1. 严禁直接把最终终局答案全盘给齐，辅导的核心在于启发学生的思维推导过程
            2. 第一步（知识定位）：若课件中有相关内容，明确指出该知识点在课件中的章节与核心背景；若上述课件内容中未提及该问题的相关信息，你必须首句明确回答：“在当前课程课件中未找到该问题的明确说明”，并结合《%s》专业知识建立引导框架
            3. 第二步（搭建脚手架）：给出 1~2 个关键线索或铺垫性定义事实，引导学生建立认知连接
            4. 第三步（递进提问）：提出 1~2 个启发性的关键递进问题，促使学生主动思考并推导核心结论
            5. 全程使用亲切耐心的中文，鼓励学生继续回答或提问
            """;

    private final StreamingChatLanguageModel streamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final QaSessionService qaSessionService;
    private final QaRecordService qaRecordService;
    private final CourseService courseService;
    private final RagConfigProperties ragProps;
    private final ObjectMapper objectMapper;
    private final QueryCondensationService queryCondensationService;
    private final InMemoryBm25Service bm25Service;
    private final SemanticCacheService semanticCacheService;
    private final ContextCompressorService contextCompressorService;
    private final LexicalSemanticRerankerService rerankerService;

    public SseStreamService(StreamingChatLanguageModel streamingChatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            QaSessionService qaSessionService,
            QaRecordService qaRecordService,
            CourseService courseService,
            RagConfigProperties ragProps,
            ObjectMapper objectMapper,
            QueryCondensationService queryCondensationService,
            InMemoryBm25Service bm25Service,
            SemanticCacheService semanticCacheService,
            ContextCompressorService contextCompressorService,
            LexicalSemanticRerankerService rerankerService) {
        this.streamingChatModel = streamingChatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.qaSessionService = qaSessionService;
        this.qaRecordService = qaRecordService;
        this.courseService = courseService;
        this.ragProps = ragProps;
        this.objectMapper = objectMapper;
        this.queryCondensationService = queryCondensationService;
        this.bm25Service = bm25Service;
        this.semanticCacheService = semanticCacheService;
        this.contextCompressorService = contextCompressorService;
        this.rerankerService = rerankerService;
    }

    /**
     * 执行 RAG 流式问答（默认直答模式 direct）
     */
    @Async("sseExecutor")
    public void streamChat(SseEmitter emitter, Long userId, Long courseId, String question, Long sessionId) {
        streamChat(emitter, userId, courseId, question, sessionId, "direct");
    }

    /**
     * 执行 RAG 流式问答，支持指定模式（direct / tutor）
     *
     * @param emitter   SSE 输出通道
     * @param userId    提问学生 ID
     * @param courseId  课程 ID
     * @param question  用户问题
     * @param sessionId 问答会话 ID
     * @param mode      答疑模式（direct: 直答模式; tutor: 启发式导师模式）
     */
    @Async("sseExecutor")
    public void streamChat(SseEmitter emitter, Long userId, Long courseId, String question, Long sessionId, String mode) {
        final long startMillis = System.currentTimeMillis();
        final boolean isTutorMode = "tutor".equalsIgnoreCase(mode);

        // ── Step 0：会话懒创建 / 归属校验（B 的 qa_session 持久化） ──────────────
        final Long actualSessionId;
        try {
            if (sessionId == null || sessionId <= 0L) {
                actualSessionId = qaSessionService.createSessionLazyForUser(userId, courseId, question);
                log.info("[SSE] 懒创建会话 sessionId={}, courseId={}", actualSessionId, courseId);
            } else {
                // 【#57 二】传入 courseId 做课程一致性校验：拦下前端切课后仍带旧 sessionId 的跳课混写
                QaSession owned = qaSessionService.getOwnedSession(sessionId, userId, courseId);
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
            // 获取当前课程上下文信息（用于为模型提供课程背景认知）
            Course currentCourse = null;
            try {
                currentCourse = courseService.getById(courseId);
            } catch (Exception e) {
                log.warn("[SSE] 获取课程上下文信息失败: courseId={}, msg={}", courseId, e.getMessage());
            }
            final String courseName = (currentCourse != null && StringUtils.hasText(currentCourse.getCourseName()))
                    ? currentCourse.getCourseName().trim()
                    : "计算机专业课程";
            final String courseDesc = (currentCourse != null && StringUtils.hasText(currentCourse.getDescription()))
                    ? currentCourse.getDescription().trim()
                    : "学科核心专业知识与核心技能";

            // ── Step 0.5：多轮对话指代消解与改写 (Query Condensation) ─────────
            // 仅在复用已有会话时尝试获取历史问答进行指代消解改写；首问直接使用原问题（零开销）
            String retrievalQuery = question;
            if (sessionId != null && sessionId > 0L) {
                try {
                    List<QaRecord> history = qaRecordService.listBySession(actualSessionId, userId);
                    retrievalQuery = queryCondensationService.condenseQuery(history, question);
                } catch (Exception e) {
                    log.warn("[SSE] 获取会话历史或改写Query失败，降级使用原问题: sessionId={}, msg={}", actualSessionId, e.getMessage());
                }
            }
            final String finalQuery = retrievalQuery;

            // ── Step 1：问题向量化（使用指代消解后的 retrievalQuery 进行精准召回） ──────
            Embedding questionEmbedding = embeddingModel.embed(finalQuery).content();

            // ── Step 1.5：高频问答语义缓存拦截 (Semantic Cache) ─────────────────
            // 仅在直答模式(direct)下拦截高频语义缓存；tutor 启发辅导模式走实时引导流
            float[] queryVector = questionEmbedding.vector();
            if (!isTutorMode) {
                Optional<SemanticCacheService.CacheEntry> cachedOpt = semanticCacheService.lookup(
                        courseId, queryVector, SemanticCacheService.DEFAULT_SIMILARITY_THRESHOLD);

                if (cachedOpt.isPresent()) {
                    SemanticCacheService.CacheEntry cached = cachedOpt.get();
                    log.info("[SSE] 命中高频问答语义缓存，直接推流: sessionId={}, 匹配原题='{}'",
                            actualSessionId, cached.getQuestion());

                    sendJsonEvent(emitter, clientGone, "references", cached.getReferences());
                    sendJsonEvent(emitter, clientGone, "message", Map.of("delta", cached.getAnswer()));

                    Long recordId;
                    try {
                        long latencyMs = System.currentTimeMillis() - startMillis;
                        recordId = qaRecordService.saveStreamingRecordAs(
                                userId, courseId, actualSessionId,
                                question, cached.getAnswer(), cached.getReferences(), latencyMs);
                    } catch (Exception e) {
                        log.error("[SSE] 缓存问答记录落库失败，sessionId={}", actualSessionId, e);
                        sendErrorQuietly(emitter, ERROR_CODE_PERSIST, "问答记录保存失败，请稍后重试");
                        emitter.complete();
                        return;
                    }

                    Map<String, Object> donePayload = Map.of(
                            "recordId", recordId,
                            "sessionId", actualSessionId,
                            "finishReason", "cache",
                            "totalTokens", 0);
                    sendJsonEvent(emitter, clientGone, "done", donePayload);
                    emitter.complete();
                    log.info("[SSE] 语义缓存回答推流完毕，recordId={}, sessionId={}", recordId, actualSessionId);
                    return;
                }
            }

            // ── Step 2：双路混合初筛与跨特征重排序 (Dense + BM25 -> RRF -> Reranker) ──
            RagConfigProperties.Chunk chunkCfg = ragProps.getChunk();
            int candidateTopK = Math.max(chunkCfg.getTopK() * 2, 6);
            Filter courseFilter = new IsEqualTo("courseId", String.valueOf(courseId));
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(questionEmbedding)
                    .filter(courseFilter)
                    .maxResults(candidateTopK)
                    .minScore(chunkCfg.getSimilarityThreshold())
                    .build();

            EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> denseMatches = searchResult.matches();
            List<InMemoryBm25Service.Bm25Match> bm25Matches = bm25Service.search(courseId, retrievalQuery, candidateTopK);

            // RRF 融合初筛候选集
            List<TextSegment> candidateSegments = bm25Service.fuseRrf(denseMatches, bm25Matches, candidateTopK);

            // 收集 Dense 与 BM25 得分映射
            Map<String, Double> denseScoreMap = new HashMap<>();
            if (denseMatches != null) {
                for (EmbeddingMatch<TextSegment> m : denseMatches) {
                    denseScoreMap.put(makeSegmentKey(m.embedded()), m.score());
                }
            }
            Map<String, Double> bm25ScoreMap = new HashMap<>();
            if (bm25Matches != null) {
                for (InMemoryBm25Service.Bm25Match bm : bm25Matches) {
                    bm25ScoreMap.put(makeSegmentKey(bm.getSegment()), bm.getScore());
                }
            }

            // 本地词汇-语义跨特征重排序 (Top-K 精炼)
            LexicalSemanticRerankerService.RerankResult rerankResult = rerankerService.rerank(
                    finalQuery, candidateSegments, denseScoreMap, bm25ScoreMap, chunkCfg.getTopK());
            List<TextSegment> finalSegments = rerankResult.rerankedSegments();
            Map<String, Double> finalScoreMap = new HashMap<>(denseScoreMap);
            finalScoreMap.putAll(rerankResult.finalScoreMap());

            List<SseReferenceVO> references = toReferencesFromSegments(finalSegments, finalScoreMap);

            String rawContext = finalSegments.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.joining("\n\n---\n\n"));

            String context = contextCompressorService.compress(rawContext, finalQuery);

            if (context.isBlank()) {
                // 【#57 三】空检索分支契约约束与答疑能力强化：
                // 1. 保留首句未找到声明契约（防幻觉）；
                // 2. 结合课程学科知识深入解答，标注【课外补充说明】，绝不拒绝回答或敷衍推脱。
                context = String.format("""
                        （本次检索未命中任何课件片段。若上述课件内容中未提及该问题的相关信息，你必须首句明确回答“在当前课程课件中未找到该问题的明确说明”，\
                        随后必须显式标注【课外补充说明】，并紧接着结合《%s》专业学科知识与教学大纲，深入、详尽、具体地全面解答学生的问题，严禁推脱或拒绝回答）""", courseName);
                log.info("[SSE] 检索结果未命中课件片段，结合课程专业知识解答: courseName={}, question={}, sessionId={}", courseName, finalQuery, actualSessionId);
            } else {
                log.info("[SSE] 检索到 {} 个相关片段，压缩后上下文长度={}, sessionId={}", references.size(), context.length(), actualSessionId);
            }

            // 契约 4.2：references 为流上第一个数据包，先出处后正文
            sendJsonEvent(emitter, clientGone, "references", references);

            // ── Step 3：拼装 RAG / Tutor 提示词 ──────────────────────────────────
            String systemPrompt;
            if (isTutorMode) {
                systemPrompt = String.format(TUTOR_SYSTEM_PROMPT, courseName, courseDesc, courseName, context, courseName);
            } else {
                systemPrompt = String.format(RAG_SYSTEM_PROMPT, courseName, courseDesc, courseName, context, courseName, courseName);
            }
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
                            ? usage.totalTokenCount()
                            : 0;

                    // 【Issue #83】finishReason 必须透传模型真实值，不能再硬编码 "stop"。
                    // 旧写法让「思考吃满 max-tokens、正文一个字没推」也上报 stop + 一笔可观的
                    // totalTokens，看起来像正常答完并花了 1500 token —— 把故障伪装成成功，
                    // 比抛错更难排查。
                    String realFinishReason = response.finishReason() == null
                            ? "stop" : response.finishReason().name().toLowerCase();

                    // 【Issue #83 流式侧】一个 delta 都没收到即空正文：走既有 error 事件通道下发
                    // 区分性原因（不再静默发 done）。记录仍保留在上一步落库里，提问不丢。
                    if (answerBuilder.length() == 0) {
                        log.error("[#83] 流式全程未产出任何 delta，sessionId={}, finishReason={}, totalTokens={}",
                                actualSessionId, realFinishReason, totalTokens);
                        sendErrorQuietly(emitter, ERROR_CODE_LLM,
                                "模型本次未产出回答内容"
                                        + ("length".equals(realFinishReason)
                                                ? "（输出预算被推理占满），请重试；若持续如此需调高 rag.llm.max-tokens 或改用非思考模式模型"
                                                : "，请稍后重试"));
                        emitter.complete();
                        return;
                    }

                    Map<String, Object> donePayload = Map.of(
                            "recordId", recordId,
                            "sessionId", actualSessionId,
                            "finishReason", realFinishReason,
                            "totalTokens", totalTokens);
                    sendJsonEvent(emitter, clientGone, "done", donePayload);
                    emitter.complete();

                    // 回填高频语义缓存（仅 direct 直答模式且回答有效、未触发防幻觉拒答时回填）
                    if (!isTutorMode && answerBuilder.length() > 0 && !answerBuilder.toString().contains("在当前课程课件中未找到该问题的明确说明")) {
                        semanticCacheService.put(courseId, finalQuery, queryVector,
                                answerBuilder.toString(), references);
                    }

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

    /** 混合检索命中 → 契约 SseReferenceVO 列表 */
    private List<SseReferenceVO> toReferencesFromSegments(List<TextSegment> segments, Map<String, Double> scoreMap) {
        List<SseReferenceVO> list = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            Map<String, Object> meta = segment.metadata().toMap();
            String key = makeSegmentKey(segment);
            Double score = scoreMap.getOrDefault(key, 0.85);
            list.add(SseReferenceVO.builder()
                    .docId(parseLongOrNull(meta.get("docId")))
                    .fileName(meta.get("fileName") == null ? null : String.valueOf(meta.get("fileName")))
                    .chunkIndex(parseIntOrNull(meta.get("chunkIndex")))
                    .score(score)
                    .snippet(abbreviate(segment.text()))
                    .build());
        }
        return list;
    }

    private static String makeSegmentKey(TextSegment segment) {
        if (segment == null) {
            return "";
        }
        Map<String, Object> meta = segment.metadata() != null ? segment.metadata().toMap() : Collections.emptyMap();
        Object docId = meta.get("docId");
        Object chunkIndex = meta.get("chunkIndex");
        if (docId != null && chunkIndex != null) {
            return docId + "_" + chunkIndex;
        }
        return String.valueOf(segment.text().hashCode());
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

    /**
     * 解析 metadata 里的 Long 型字段（docId / courseId）。
     *
     * <p>【Issue #82】不能只写 {@code Long.parseLong(String.valueOf(v))}，理由同
     * {@link #parseIntOrNull(Object)}：经 Chroma 往返后数值型 metadata 会变成
     * <b>带小数点的字符串</b>，直接 parse 必抛并静默丢值。</p>
     */
    static Long parseLongOrNull(Object value) {
        String digits = toIntegerDigitsOrNull(value);
        if (digits == null) {
            warnUnparsableMetadata("Long", value);
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            warnUnparsableMetadata("Long", value);
            return null;
        }
    }

    /**
     * 解析 metadata 里的 Integer 型字段（chunkIndex）。
     *
     * <p><b>【Issue #82 根因，已运行时实测】</b>写入侧 {@code metadata.put("chunkIndex", i)}
     * 存的是 int，但经 Chroma（JSON 序列化 + Gson 反序列化）往返后，取回来的是
     * <b>{@code java.lang.String}，内容为 {@code "3.0"}</b>——实测输出：
     * {@code chunkIndex.class = java.lang.String}、{@code value = 3.0}。
     * 于是 {@code Integer.parseInt("3.0")} 抛 NumberFormatException，旧实现<b>静默返回 null</b>，
     * 教师端引用详情就显示成「第 段」。</p>
     *
     * <p><b>为什么 InMemory 下测不出来</b>：进程内 store 不做 JSON 往返，metadata 原样保留，
     * 所以既有 6 个测试类全绿也照不出这个缺陷——它只在真实 Chroma 下暴露。
     * 这也是 #58 审查里那条「InMemory 白送语义」教训的同一族问题。</p>
     *
     * <p>按 #82 的验收要求：<b>只接受合法整数</b>。小数（3.5）、NaN、非数值一律返回 null
     * 并记 WARN，<b>不做静默截断</b>——截断会把「第 3 段」显示成「第 0 段」，比 null 更难发现。</p>
     */
    static Integer parseIntOrNull(Object value) {
        String digits = toIntegerDigitsOrNull(value);
        if (digits == null) {
            warnUnparsableMetadata("Integer", value);
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            warnUnparsableMetadata("Integer", value);
            return null;
        }
    }

    /**
     * 把 metadata 值规整为「纯整数字面量」字符串；无法确定其为整数时返回 {@code null}。
     *
     * <p>接受三类输入：整数字面量（{@code "3"}）、浮点形式（{@code "3.0"}，Chroma 往返产物）、
     * {@link Number} 实例。用 {@link java.math.BigDecimal#toBigIntegerExact()} 判定：
     * {@code 3.0 → "3"}，而 {@code 3.5} 抛 {@link ArithmeticException} → 返回 null（**不截断**）。</p>
     */
    private static String toIntegerDigitsOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.matches("[-+]?\\d+")) {
            return text;
        }
        try {
            return new java.math.BigDecimal(text).toBigIntegerExact().toString();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    /** 解析失败必须留痕：旧实现静默吞掉，导致「教师端编号为空」在日志里毫无线索（Issue #82）。 */
    private static void warnUnparsableMetadata(String targetType, Object value) {
        log.warn("[#82] 引用元数据无法解析为 {}，已置 null（不静默截断）: value={}, class={}",
                targetType, value, value == null ? "null" : value.getClass().getName());
    }
}
