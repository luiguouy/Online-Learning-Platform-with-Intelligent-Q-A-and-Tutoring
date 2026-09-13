package com.smartqa.platform.controller;

import com.smartqa.platform.service.rag.SseStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 流式问答接口（A1.5）
 *
 * 接口路径：GET /api/sse/chat
 * 返回类型：text/event-stream（Server-Sent Events）
 *
 * SSE 事件格式：
 *   event: message  →  data: &lt;token&gt;      （每个 AI 回复 Token）
 *   event: done     →  data: [DONE]         （流结束标记）
 *   event: error    →  data: &lt;错误信息&gt;   （异常时推送）
 */
@Tag(name = "智能问答", description = "RAG 流式问答接口（SSE）")
@RestController
@RequestMapping("/api/sse")
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
     * @param sessionId 问答会话 ID（可选；首次提问传 null，续问传上次返回的 sessionId）
     * @return SseEmitter（Spring 负责以 text/event-stream 格式推送）
     */
    @Operation(summary = "RAG 流式问答（SSE）",
               description = "基于课件向量检索 + 大模型流式输出，前端通过 EventSource 接收逐 Token 回复")
    @GetMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(
            @Parameter(description = "课程 ID", required = true)
            @RequestParam Long courseId,

            @Parameter(description = "学生问题", required = true)
            @RequestParam String question,

            @Parameter(description = "会话 ID（首次提问传空，续问传上次 sessionId）")
            @RequestParam(required = false) Long sessionId) {

        // 超时时间 120 秒（与 application.yml rag.llm.timeout-seconds 保持裕量）
        SseEmitter emitter = new SseEmitter(120_000L);

        // 在异步线程池中执行 RAG 检索 + 流式推送，避免阻塞 Tomcat 工作线程
        sseStreamService.streamChat(emitter, courseId, question, sessionId);

        return emitter;
    }
}
