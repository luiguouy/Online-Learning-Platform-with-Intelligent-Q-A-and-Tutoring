package com.smartqa.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.entity.Course;
import com.smartqa.platform.mapper.CourseMapper;
import com.smartqa.platform.service.CourseService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 课程服务实现。
 *
 * @author 成员 B
 */
@Service
public class CourseServiceImpl extends ServiceImpl<CourseMapper, Course> implements CourseService {

    private static final String ROLE_TEACHER = "TEACHER";

    @Override
    public List<Course> listVisibleCourses(Long userId, String role) {
        if (ROLE_TEACHER.equals(role)) {
            if (userId == null) {
                throw new BusinessException(401, "未取到登录用户，无法查询课程");
            }
            // 教师视角：只看自己任课的课程
            return list(Wrappers.<Course>lambdaQuery()
                    .eq(Course::getTeacherId, userId)
                    .orderByDesc(Course::getCreatedAt));
        }
        // 学生视角：本期 6 张表里没有选课关系表（功能范围已冻结，不允许自行加表），
        // 因此学生默认可进入任意课程提问。
        return list(Wrappers.<Course>lambdaQuery()
                .orderByDesc(Course::getCreatedAt));
    }

    @Override
    public Course assertTeacherOwnsCourse(Long courseId, Long teacherId) {
        if (courseId == null) {
            throw new BusinessException("课程ID不能为空");
        }
        Course course = getById(courseId);
        if (course == null) {
            throw new BusinessException(404, "课程不存在");
        }
        if (!Objects.equals(course.getTeacherId(), teacherId)) {
            throw new BusinessException(403, "无权操作该课程");
        }
        return course;
    }
}
