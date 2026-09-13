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
 * 课程实体（对应表 {@code course}）。
 *
 * <p>本期课程的新增 / 编辑不在功能范围内（教师端只读），
 * 但保留完整 CRUD 能力以便后续扩展。</p>
 *
 * @author 成员 B
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("course")
@Schema(description = "课程")
public class Course implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "课程ID")
    private Long id;

    @Schema(description = "课程名称，如：操作系统原理")
    private String courseName;

    @Schema(description = "课程编号，如：CS202401（唯一）")
    private String courseCode;

    @Schema(description = "任课教师ID")
    private Long teacherId;

    @Schema(description = "课程简介")
    private String description;

    @Schema(description = "课程封面图")
    private String coverImage;

    @TableLogic
    @Schema(description = "逻辑删除：0-正常，1-删除")
    private Integer isDeleted;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
