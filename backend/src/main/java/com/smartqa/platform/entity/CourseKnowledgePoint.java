package com.smartqa.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 课程核心知识点与考点库实体（对应表 {@code course_knowledge_point}）。
 *
 * <p>知识点内容由成员 A 的知识点生成接口写入，本模块只负责建模与持久化。</p>
 *
 * @author 成员 B
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("course_knowledge_point")
@Schema(description = "课程核心知识点")
public class CourseKnowledgePoint implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "知识点ID")
    private Long id;

    @Schema(description = "关联课程ID")
    private Long courseId;

    @Schema(description = "所属章节")
    private String chapterName;

    @Schema(description = "知识点标题，如：页面置换算法LRU与FIFO对比")
    private String title;

    @Schema(description = "核心精解摘要")
    private String summary;

    @TableLogic
    @Schema(description = "逻辑删除：0-正常，1-删除")
    private Integer isDeleted;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
