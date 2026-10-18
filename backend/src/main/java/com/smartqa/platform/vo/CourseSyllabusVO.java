package com.smartqa.platform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 课程知识大纲与考点全景视图对象 (Course Syllabus VO)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "课程知识大纲与章节全景视图")
public class CourseSyllabusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "课程ID")
    private Long courseId;

    @Schema(description = "课程名称")
    private String courseName;

    @Schema(description = "章节大纲树节点列表")
    private List<ChapterNode> chapters;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "章节大纲节点")
    public static class ChapterNode implements Serializable {
        private static final long serialVersionUID = 1L;

        @Schema(description = "章节标题或面包屑路径")
        private String title;

        @Schema(description = "关联课件文件名")
        private String fileName;

        @Schema(description = "子小节列表")
        private List<String> sections;

        @Schema(description = "本章涵盖的核心知识考点列表")
        private List<String> knowledgePoints;
    }
}
