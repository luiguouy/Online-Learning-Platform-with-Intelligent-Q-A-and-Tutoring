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
    private static final int ERROR_CODE_BUSY = 5003;

    /**
     * 提问文本长度上限（字符数）。
     *
     * <p><b>本值与 {@code application.yml} 的 {@code server.max-http-request-header-size}
     * 是配套的，不能单独改。</b>契约把 question 放在 GET query string，Tomcat 在
     * {@code Http11InputBuffer.parseRequestLine} 处对「请求行 + 所有请求头」做长度校验，
     * 超限的请求<b>根本进不到本方法</b>，容器直接返回 HTTP 400，前端 EventSource
     * 只能看到一个没有任何信息的失败。</p>
     *
     * <p>实测（原始 socket 二分 + 精确记账；Tomcat 10.1 / Spring Boot 3.3.5）：
     * 该上限<b>恰好等于 {@code max-http-request-header-size} 的字节值（1:1）</b>，
     * 计数口径为「请求行 + 所有请求头 + 各行 CRLF + 结束空行」之和——默认 8KB 时 8192 字节，
     * 配 64KB 时 65536 字节；<b>超限返回 HTTP 400，不是 431</b>。
     * 配 64KB 下本值 1600 字（中文 ≈14400 字节）仅占预算 22%，协议层余量充足。</p>
     *
     * <p>⚠️ 真正的耦合点在<b>下限</b>：若删掉那项配置，上限掉回 8192 字节，
     * 实测中文提问<b>超过 888 字</b>（8189 字节通过 / 889 字 8198 字节被拒）即被容器
     * 以 HTTP 400 拒掉、进不到本方法，远低于本值 1600。
     * 也就是说"超长提问走 event:error 友好返回"这条保障，依赖 64KB 那项配置成立。</p>
     *
     * <p><b>无需再做 UTF-8 字节级校验</b>（2026-09-30 实测定论，<b>勿按旧数据加回</b>）：
     * 协议上限 65536 字节，而本值 1600 个字符即便全是 4 字节字符（emoji / CJK 扩展 B），
     * 也只 ≈19200 编码字节，离天花板尚远，且超限返回 HTTP 400 而非 431。</p>
     *
     * <p>曾并存过一个 {@code MAX_QUESTION_UTF8_BYTES=5300} 字节闸，已按审查裁定整条移除。
     * 移除的<b>真正理由</b>是它<b>不可达（死代码）</b>：{@code String.length()} 计的是
     * UTF-16 码元，而每个 UTF-16 码元最多产出 3 个 UTF-8 字节（ASCII = 1；3 字节 CJK = 3；
     * 4 字节 emoji 是代理对，4 字节 / 2 码元 = 2）。故 length() 不超过 1600 时，
     * UTF-8 字节最多 3 x 1600 = 4800，恒小于 5300 —— 字节分支<b>永远不可能在字符分支
     * 为 false 时单独成立</b>，它对可观测行为零贡献，任何测试都无法区分它在与不在。
     * （附：审查意见里"1600 个 4 字节字符会先于字符闸触发"的算例不成立 ——
     * 1600 个 emoji 的 length() 是 3200，拦下它的是本字符闸。此处以实测算术为准。）</p>
     *
     * <p>遗留（独立于本次移除，未擅自改，待裁定）：因 length() 计 UTF-16 码元，
     * 用户视觉上的"1600 个 emoji"会被报"最多 1600 字"，这是既有的语义落差。</p>
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
            @RequestParam(required = false) Long courseId,

            @Parameter(description = "学生问题", required = true)
            @RequestParam(required = false) String question,

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
        // 【审查 M2】必填参数缺失的校验必须在方法内做：若依赖 @RequestParam 的 required 检查，
        // MissingServletRequestParameterException 会被 produces=text/event-stream 的内容协商
        // 卡住（advice 回 JSON 被拒）→ HTTP 500 空体，与 B3.1「空/缺参友好返回不 500」目标相背。
        // 改为 required=false + 手动拦截，统一走 SSE error 通道。
        if (courseId == null || !StringUtils.hasText(question)) {
            log.warn("[SSE] 缺少必要参数或提问为空，userId={}, courseId={}", userId, courseId);
            sendErrorEvent(emitter, ERROR_CODE_PARAM, "缺少必要参数：courseId / question");
            return emitter;
        }

        // 【边界】超长提问：同样的理由走 SSE error 事件，给前端可读提示。
        // 只校验字符数（见 MAX_QUESTION_LENGTH Javadoc：字节级校验的立论已被实测推翻、已移除）。
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
