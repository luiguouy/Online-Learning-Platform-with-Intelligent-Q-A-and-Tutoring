package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.dto.KnowledgeGenerateDTO;
import com.smartqa.platform.service.rag.KnowledgeService;
import com.smartqa.platform.vo.KnowledgeGenerateVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识点深度解析接口（A2.5）。
 *
 * <p>路径 {@code POST /api/knowledge/generate} 与接口矩阵（{@code TEAM_WORK_DIVISION.md} 第三章）逐字符一致，
 * 成员 C 的学生端知识点面板按此对接。</p>
 *
 * <p>鉴权与限流：本接口单次调用消耗大量大模型 Token，必须登录（下方 {@code checkLogin} 为显式保险，
 * 全局 {@code /api/**} 已在 Sa-Token 拦截器默认强制登录）；按用户限流由 {@code QaRateLimitInterceptor}
 * 注册在 {@code /api/knowledge/generate} 上完成。</p>
 *
 * @author 成员 A
 */
@Tag(name = "知识点解析", description = "基于课件知识库的知识点结构化精解生成")
@RestController
@RequestMapping("/api/knowledge")
@Slf4j
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Operation(summary = "生成知识点精解",
            description = "按 courseId 检索本课课件作为上下文，返回结构化 Markdown 精解（核心概念 + 难点辨析），不含自测题")
    @PostMapping("/generate")
    public Result<KnowledgeGenerateVO> generate(@Valid @RequestBody KnowledgeGenerateDTO dto) {
        // 必须登录：未登录 checkLogin 抛 NotLoginException，由 GlobalExceptionHandler 统一转 401
        StpUtil.checkLogin();
        log.info("[Knowledge] 知识点解析请求 userId={}, courseId={}, point={}",
                StpUtil.getLoginIdAsLong(), dto.getCourseId(), dto.getKnowledgePoint());
        return Result.success(knowledgeService.generate(dto));
    }
}
