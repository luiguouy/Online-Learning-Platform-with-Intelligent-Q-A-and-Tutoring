package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartqa.platform.service.rag.SseStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * SSE 流式问答接口（A1.5）
 *
 * 接口路径：GET /api/qa/chat/stream（接口矩阵冻结路径，见 TEAM_WORK_DIVISION.md 第三章；
 * B 的限流拦截器注册在同一路径，改路径会导致限流失效）
 * 返回类型：text/event-stream（Server-Sent Events）
 *
 * SSE 事件格式（DEV_SPECIFICATION.md 4.2 冻结，data 一律为 JSON）：
 *   event: references → [{"docId","fileName","chunkIndex","score","snippet"}]  首包出处
 *   event: message   → {"delta": "<增量文本>"}                                流式吐字
 *   event: done      → {"recordId","sessionId","finishReason","totalTokens"}  结束标记
 *   event: error     → {"errorCode","message"}                                异常中断
 */
@Tag(name = "智能问答", description = "RAG 流式问答接口（SSE）")
@RestController
@RequestMapping("/api/qa")
@Slf4j
public class SseChatController {

    /**
     * SSE error 事件的错误码（与 SseStreamService 口径对齐）：
     * 参数类错误走 4000，区别于 5000（内部异常）/ 5001（模型异常）/ 5002（落库失败）。
     */
    private static final int ERROR_CODE_PARAM = 4000;

    /**
     * 线程池饱和时的错误码。
     * 与 SseStreamService 及成员 A 的加固口径一致：5003=服务繁忙（队列打满）。
     */
    private static final int ERROR_CODE_BUSY = 5003;    /**
     * 提问文本长度上限（字符数，按中文估算）。
     *
     * <p><b>这个值由 Tomcat 的请求行长度上限反推得到，不是随手写的。</b>
     * 契约把 question 放在 GET query string，Tomcat 在
     * {@code Http11InputBuffer.parseRequestLine} 处对「请求行 + 请求头」做长度校验，
     * 超限的请求<b>根本进不到本方法</b>，容器直接返回 431/400，前端 EventSource
     * 只能看到一个没有任何信息的失败。</p>
     *
     * <p>实测（二分逼近）本机 Tomcat 10.1 的请求行上限：
     * 默认约 8056 字节；配置 {@code server.max-http-request-header-size: 64KB} 后约 16370 字节。
     * 中文 URL 编码后约 9 字节/字，故 1600 字 ≈ 14400 字节 &lt; 16370，留约 2KB 余量。</p>
     *
     * <p>⚠️ 本值与 {@code application.yml} 的 {@code max-http-request-header-size} 是配套的：
     * 若去掉那项配置，上限会掉回 8056 字节，本值必须同步下调，否则超长提问仍会被协议层断连。</p>
     */
    private static final int MAX_QUESTION_LENGTH = 1600;

    private final SseStreamService sseStreamService;
    private final ObjectMapper objectMapper;

    public SseChatController(SseStreamService sseStreamService, ObjectMapper objectMapper) {
        this.sseStreamService = sseStreamService;
        this.objectMapper = objectMapper;
    }

    /**
     * RAG 流式问答（SSE）
     *
     * @param courseId  课程 ID（必填，检索范围限定在当前课程的课件）
     * @param question  学生提问内容（必填）
     * @param sessionId 问答会话 ID；传 0 或省略时懒创建新会话，续问传上次 done 包返回的 sessionId
     * @return SseEmitter（Spring 负责以 text/event-stream 格式推送）
     */
    @Operation(summary = "RAG 流式问答（SSE）",
               description = "基于课件向量检索 + 大模型流式输出，前端通过 EventSource 接收逐 Token 回复")
    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(
            @Parameter(description = "课程 ID", required = true)
            @RequestParam Long courseId,

            @Parameter(description = "学生问题", required = true)
            @RequestParam String question,

            @Parameter(description = "会话 ID（首次提问传 0 或省略，续问传上次 done 包的 sessionId）")
            @RequestParam(required = false) Long sessionId) {

        // 关键：登录态必须在请求线程取（Sa-Token 是 ThreadLocal），
        // 传给 sseExecutor 异步线程里的 streamChat —— 它拿不到登录态。
        long userId = StpUtil.getLoginIdAsLong();

        // 超时时间 120 秒（与 application.yml rag.llm.timeout-seconds 保持裕量）
        SseEmitter emitter = new SseEmitter(120_000L);

        // 【边界】空 / 纯空白提问在进入异步链路前拦下，否则会一路走到
        // embeddingModel.embed()，抛 IllegalArgumentException: text cannot be null or blank，
        // 兜底成 event:error {errorCode:5000}——用户传了空参数却表现成"服务内部异常"。
        //
        // 注意：这里**不能**直接 throw BusinessException。本接口 produces=text/event-stream，
        // 而 @RestControllerAdvice 返回的是 JSON 的 Result；前端 EventSource 一定带
        // Accept: text/event-stream，内容协商将拒绝序列化 JSON → 抛
        // HttpMediaTypeNotAcceptableException → 落到 Tomcat 默认错误页，最终返回
        // HTTP 500 空响应体（实测复现）。正确做法是走 SSE 通道，用 error 事件表达，
        // 与 4.2 契约的 {"errorCode":..,"message":".."} 对齐。
        if (!StringUtils.hasText(question)) {
            log.warn("[SSE] 提问内容为空白，userId={}, courseId={}", userId, courseId);
            sendErrorEvent(emitter, ERROR_CODE_PARAM, "提问内容不能为空");
            return emitter;
        }

        // 【边界】超长提问：同样的理由走 SSE error 事件，给前端可读提示。
        if (question.length() > MAX_QUESTION_LENGTH) {
            log.warn("[SSE] 提问过长，userId={}, length={}", userId, question.length());
            sendErrorEvent(emitter, ERROR_CODE_PARAM,
                    "提问内容过长（最多 " + MAX_QUESTION_LENGTH + " 字），请精简后重试");
            return emitter;
        }

        // 在异步线程池中执行 RAG 检索 + 流式推送，避免阻塞 Tomcat 工作线程。
        // sseExecutor 采用 AbortPolicy：队列+线程打满时 @Async 提交会抛 TaskRejectedException，
        // 这里捕获后立刻给客户端下发 error 事件并结束，而不是拖占 Tomcat 线程（防级联 DoS）。
        try {
            sseStreamService.streamChat(emitter, userId, courseId, question, sessionId);
        } catch (RejectedExecutionException e) {
            log.warn("[SSE] 问答线程池饱和，拒绝请求 userId={}, courseId={}", userId, courseId);
            sendErrorEvent(emitter, ERROR_CODE_BUSY, "服务繁忙，请稍后重试");
        }

        return emitter;
    }

    /**
     * 通过 SSE 通道下发 error 事件并正常收尾。
     *
     * <p>不能用抛异常的方式表达本接口的参数错误：本接口 produces=text/event-stream，
     * 全局 @RestControllerAdvice 返回 JSON 的 Result，前端 EventSource 带的
     * Accept: text/event-stream 会让内容协商拒绝写 JSON，最终变成 HTTP 500 空体。
     * 走 SSE 事件既能保持 HTTP 200 的统一契约，也能让前端 EventSource 正常收到结构化错误。</p>
     */
    private void sendErrorEvent(SseEmitter emitter, int errorCode, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(
                            Map.of("errorCode", errorCode, "message", message))));
        } catch (Exception e) {
            // 客户端已断开等场景：静默收尾，不再抛给容器
            log.warn("[SSE] error 事件下发失败（客户端可能已断开）: {}", e.getMessage());
        }
        emitter.complete();
    }
}
