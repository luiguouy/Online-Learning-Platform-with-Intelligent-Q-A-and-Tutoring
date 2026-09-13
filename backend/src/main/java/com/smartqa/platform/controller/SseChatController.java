package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.service.rag.SseStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
public class SseChatController {

    private final SseStreamService sseStreamService;

    public SseChatController(SseStreamService sseStreamService) {
        this.sseStreamService = sseStreamService;
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

        // 在异步线程池中执行 RAG 检索 + 流式推送，避免阻塞 Tomcat 工作线程
        sseStreamService.streamChat(emitter, userId, courseId, question, sessionId);

        return emitter;
    }
}
