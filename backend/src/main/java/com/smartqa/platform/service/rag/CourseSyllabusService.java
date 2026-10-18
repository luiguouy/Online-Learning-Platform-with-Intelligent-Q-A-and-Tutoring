package com.smartqa.platform.service.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.entity.Course;
import com.smartqa.platform.entity.CourseDocument;
import com.smartqa.platform.entity.CourseKnowledgePoint;
import com.smartqa.platform.mapper.CourseKnowledgePointMapper;
import com.smartqa.platform.service.CourseDocumentService;
import com.smartqa.platform.service.CourseService;
import com.smartqa.platform.vo.CourseSyllabusVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 课程大纲感知与考点导学服务 (Course Syllabus & Outline Perception)
 *
 * 核心目标：
 * 1. 聚合课程课件文件树与大纲层级，提供完整的课程知识全景视图；
 * 2. 映射挂载 course_knowledge_point 核心考点库，赋能 Tutor Agent 结构化定位；
 * 3. 严格遵循 HANDOFF SEC-1 铁律：课件对登录用户公开，仅做 courseId 范围隔离，不加权限校验。
 */
@Service
public class CourseSyllabusService {

    private static final Logger log = LoggerFactory.getLogger(CourseSyllabusService.class);

    private final CourseService courseService;
    private final CourseDocumentService courseDocumentService;
    private final CourseKnowledgePointMapper knowledgePointMapper;

    public CourseSyllabusService(CourseService courseService,
                                 CourseDocumentService courseDocumentService,
                                 CourseKnowledgePointMapper knowledgePointMapper) {
        this.courseService = courseService;
        this.courseDocumentService = courseDocumentService;
        this.knowledgePointMapper = knowledgePointMapper;
    }

    /**
     * 获取指定课程的完整知识大纲与考点全景树
     *
     * @param courseId 课程 ID
     * @return 结构化课程大纲 VO
     */
    public CourseSyllabusVO getCourseSyllabus(Long courseId) {
        if (courseId == null) {
            throw new BusinessException(400, "课程ID不能为空");
        }

        Course course = courseService.getById(courseId);
        if (course == null) {
            throw new BusinessException(404, "课程不存在");
        }

        // 1. 获取该课程下的全部课件
        List<CourseDocument> docs = courseDocumentService.listByCourse(courseId);

        // 2. 获取该课程库中沉淀的所有知识考点
        List<CourseKnowledgePoint> points = knowledgePointMapper.selectList(
                Wrappers.<CourseKnowledgePoint>lambdaQuery()
                        .eq(CourseKnowledgePoint::getCourseId, courseId)
                        .orderByAsc(CourseKnowledgePoint::getId)
        );

        // 按章节名称对知识点进行分组映射
        Map<String, List<String>> pointsByChapter = new HashMap<>();
        if (points != null) {
            for (CourseKnowledgePoint p : points) {
                String ch = StringUtils.hasText(p.getChapterName()) ? p.getChapterName().trim() : "其他重点章节";
                pointsByChapter.computeIfAbsent(ch, k -> new ArrayList<>()).add(p.getTitle());
            }
        }

        // 3. 构建章节节点列表
        List<CourseSyllabusVO.ChapterNode> chapters = new ArrayList<>();
        if (docs != null) {
            for (CourseDocument doc : docs) {
                String fileName = doc.getFileName();
                // 默认从文件名提取大章节名，如 "第3章_内存管理.md" -> "第3章 内存管理"
                String cleanTitle = fileName.replace(".md", "").replace("_", " ").trim();

                // 关联挂载本章知识考点
                List<String> matchedPoints = new ArrayList<>();
                for (Map.Entry<String, List<String>> entry : pointsByChapter.entrySet()) {
                    if (cleanTitle.contains(entry.getKey()) || entry.getKey().contains(cleanTitle)) {
                        matchedPoints.addAll(entry.getValue());
                    }
                }

                chapters.add(CourseSyllabusVO.ChapterNode.builder()
                        .title(cleanTitle)
                        .fileName(fileName)
                        .sections(List.of("概述与背景", "核心机制与原理", "重点难点解析"))
                        .knowledgePoints(matchedPoints)
                        .build());
            }
        }

        log.info("[Syllabus] 成功组装课程大纲: courseId={}, 课件数={}, 章节数={}",
                courseId, (docs != null ? docs.size() : 0), chapters.size());

        return CourseSyllabusVO.builder()
                .courseId(course.getId())
                .courseName(course.getCourseName())
                .chapters(chapters)
                .build();
    }
}
