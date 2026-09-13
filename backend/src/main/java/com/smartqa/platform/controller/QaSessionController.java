package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.dto.FeedbackDTO;
import com.smartqa.platform.entity.QaRecord;
import com.smartqa.platform.service.QaRecordService;
import com.smartqa.platform.service.QaSessionService;
import com.smartqa.platform.vo.SessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生端问答记录接口：会话历史 / 会话明细 / 点赞点踩。
 *
 * <p>路径与 {@code TEAM_WORK_DIVISION.md} 接口矩阵逐字符一致，前端已按此写死。</p>
 *
 * @author 成员 B
 */
@Tag(name = "问答记录模块")
@RestController
@RequestMapping("/api/qa")
@RequiredArgsConstructor
public class QaSessionController {

    private final QaSessionService qaSessionService;
    private final QaRecordService qaRecordService;

    @GetMapping("/sessions")
    @Operation(summary = "会话历史列表（只返回当前学生自己的会话）")
    public Result<List<SessionVO>> listSessions(@RequestParam("courseId") Long courseId) {
        return Result.success(qaSessionService.listMySessions(courseId, StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/records")
    @Operation(summary = "会话明细（按提问时间正序，直接从上往下渲染对话）")
    public Result<List<QaRecord>> listRecords(@RequestParam("sessionId") Long sessionId) {
        return Result.success(qaRecordService.listBySession(sessionId, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/records/{id}/feedback")
    @Operation(summary = "点赞 / 点踩（status 只能是 1 或 -1）")
    public Result<Boolean> feedback(@PathVariable("id") Long id,
                                    @Valid @RequestBody FeedbackDTO dto) {
        qaRecordService.updateFeedback(id, dto.getStatus(), StpUtil.getLoginIdAsLong());
        return Result.success(true);
    }
}
