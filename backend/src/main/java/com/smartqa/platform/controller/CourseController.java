package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.entity.Course;
import com.smartqa.platform.service.CourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程接口。
 *
 * <p>本期只实现 {@code GET /api/course/list}（接口矩阵中唯一由学生端/教师端调用的课程接口）；
 * 课程的增删改不在功能范围内。</p>
 *
 * @author 成员 B
 */
@Tag(name = "课程模块")
@RestController
@RequestMapping("/api/course")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @GetMapping("/list")
    @Operation(summary = "课程列表（教师视角只看自己任课，学生视角看全部）")
    public Result<List<Course>> list() {
        Long userId = StpUtil.getLoginIdAsLong();
        // 登录时 AuthController 已把角色写入 Sa-Token Session；
        // 取不到时按 STUDENT 处理（学生可见全部课程，不会造成越权）
        String role = (String) StpUtil.getSession().get("role");
        return Result.success(courseService.listVisibleCourses(userId, role));
    }
}
