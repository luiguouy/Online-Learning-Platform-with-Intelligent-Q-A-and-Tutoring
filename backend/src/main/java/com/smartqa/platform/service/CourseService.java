package com.smartqa.platform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartqa.platform.entity.Course;

import java.util.List;

/**
 * 课程服务。
 *
 * @author 成员 B
 */
public interface CourseService extends IService<Course> {

    /**
     * 查询当前用户可见的课程列表。
     *
     * <p>视角差异：</p>
     * <ul>
     *   <li>TEACHER：只能看到自己任课的课程（teacher_id = userId）</li>
     *   <li>STUDENT：看到全部课程。本期 6 张表里没有选课关系表
     *       （功能范围已冻结，不允许自行加表），因此学生默认可进入任意课程提问</li>
     * </ul>
     *
     * @param userId 当前登录用户 ID
     * @param role   当前登录用户角色
     * @return 课程列表，按创建时间倒序
     */
    List<Course> listVisibleCourses(Long userId, String role);

    /**
     * 校验课程存在且归当前教师所有，否则抛 BusinessException。
     *
     * <p>教师端所有写操作（上传 / 删除 / 重建索引）与列表查询都必须先过这道闸。</p>
     *
     * @param courseId  课程 ID
     * @param teacherId 当前登录教师 ID
     * @return 课程实体（避免调用方二次查询）
     */
    Course assertTeacherOwnsCourse(Long courseId, Long teacherId);
}
