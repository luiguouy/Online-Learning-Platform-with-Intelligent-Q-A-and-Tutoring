package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.entity.QaRecord;
import com.smartqa.platform.service.CourseService;
import com.smartqa.platform.service.QaRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师端问答记录查看接口。
 *
 * <p>功能范围说明：教师后台只做问答记录的<b>查看</b>，不提供修改 AI 回答（人工纠偏）功能，
 * 因此本 Controller 是<b>纯只读</b>的 —— 只有 GET 查询，没有任何写接口。</p>
 *
 * @author 成员 B
 */
@Tag(name = "教师端-问答记录查看")
@RestController
@RequestMapping("/api/teacher/qa")
@RequiredArgsConstructor
public class TeacherQaController {

    private final QaRecordService qaRecordService;
    private final CourseService courseService;

    @GetMapping("/records")
    @Operation(summary = "分页查询学生提问明细（只读）")
    public Result<IPage<QaRecord>> listRecords(
            @RequestParam("courseId") Long courseId,
            @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(value = "keyword", required = false) String keyword) {
        // 越权保护：教师只能查看自己任课课程的问答记录
        Long teacherId = StpUtil.getLoginIdAsLong();
        courseService.assertTeacherOwnsCourse(courseId, teacherId);

        // 按课程 + 关键词（匹配 question 或 answer）分页查询，按提问时间倒序
        return Result.success(qaRecordService.pageRecords(courseId, pageNum, pageSize, keyword));
    }
}
