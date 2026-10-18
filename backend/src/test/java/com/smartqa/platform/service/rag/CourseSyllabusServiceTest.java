package com.smartqa.platform.service.rag;

import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.entity.Course;
import com.smartqa.platform.entity.CourseDocument;
import com.smartqa.platform.entity.CourseKnowledgePoint;
import com.smartqa.platform.mapper.CourseKnowledgePointMapper;
import com.smartqa.platform.service.CourseDocumentService;
import com.smartqa.platform.service.CourseService;
import com.smartqa.platform.vo.CourseSyllabusVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 课程大纲感知与考点导学单元测试 (Course Syllabus Service)
 */
class CourseSyllabusServiceTest {

    private CourseService courseService;
    private CourseDocumentService courseDocumentService;
    private CourseKnowledgePointMapper knowledgePointMapper;
    private CourseSyllabusService syllabusService;

    @BeforeEach
    void setUp() {
        courseService = Mockito.mock(CourseService.class);
        courseDocumentService = Mockito.mock(CourseDocumentService.class);
        knowledgePointMapper = Mockito.mock(CourseKnowledgePointMapper.class);
        syllabusService = new CourseSyllabusService(courseService, courseDocumentService, knowledgePointMapper);
    }

    @Test
    @DisplayName("课程不存在时抛出 404 BusinessException")
    void testCourseNotFound() {
        when(courseService.getById(999L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> syllabusService.getCourseSyllabus(999L));
    }

    @Test
    @DisplayName("参数为空时抛出参数异常")
    void testNullCourseId() {
        assertThrows(BusinessException.class, () -> syllabusService.getCourseSyllabus(null));
    }

    @Test
    @DisplayName("成功组装章节树与关联挂载知识考点")
    void testBuildSyllabusTree() {
        long courseId = 101L;
        Course course = Course.builder()
                .id(courseId)
                .courseName("操作系统原理")
                .build();
        when(courseService.getById(courseId)).thenReturn(course);

        CourseDocument doc1 = CourseDocument.builder()
                .id(1L)
                .courseId(courseId)
                .fileName("第2章_进程管理.md")
                .build();
        CourseDocument doc2 = CourseDocument.builder()
                .id(2L)
                .courseId(courseId)
                .fileName("第3章_内存管理.md")
                .build();
        when(courseDocumentService.listByCourse(courseId)).thenReturn(List.of(doc1, doc2));

        CourseKnowledgePoint kp1 = CourseKnowledgePoint.builder()
                .id(10L)
                .courseId(courseId)
                .chapterName("进程管理")
                .title("进程互斥与信号量机制")
                .build();
        CourseKnowledgePoint kp2 = CourseKnowledgePoint.builder()
                .id(11L)
                .courseId(courseId)
                .chapterName("内存管理")
                .title("虚拟内存与页面置换LRU")
                .build();
        when(knowledgePointMapper.selectList(any())).thenReturn(List.of(kp1, kp2));

        CourseSyllabusVO vo = syllabusService.getCourseSyllabus(courseId);

        assertNotNull(vo);
        assertEquals(courseId, vo.getCourseId());
        assertEquals("操作系统原理", vo.getCourseName());
        assertEquals(2, vo.getChapters().size());

        // 校验进程管理章节
        CourseSyllabusVO.ChapterNode ch1 = vo.getChapters().get(0);
        assertTrue(ch1.getTitle().contains("进程管理"));
        assertEquals("第2章_进程管理.md", ch1.getFileName());
        assertTrue(ch1.getKnowledgePoints().contains("进程互斥与信号量机制"));

        // 校验内存管理章节
        CourseSyllabusVO.ChapterNode ch2 = vo.getChapters().get(1);
        assertTrue(ch2.getTitle().contains("内存管理"));
        assertEquals("第3章_内存管理.md", ch2.getFileName());
        assertTrue(ch2.getKnowledgePoints().contains("虚拟内存与页面置换LRU"));
    }
}
