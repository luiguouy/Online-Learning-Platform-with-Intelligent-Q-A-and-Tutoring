package com.smartqa.platform.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * 课程课件资料实体（对应表 {@code course_document}）。
 *
 * <p>解析状态机四态命名已冻结，禁止使用别名：</p>
 * <pre>
 *   PENDING（排队中） → PARSING（切块向量化中） → CHUNKED（已就绪）
 *                                            ↘ FAILED（解析失败，需写 error_msg）
 * </pre>
 * 前端按这四个字符串做标签映射与轮询开关，改一个字都会让前端显示异常。
 *
 * @author 成员 B
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("course_document")
@Schema(description = "课程课件资料")
public class CourseDocument implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 排队中（本项目同步提交切块任务，正常流程基本不会落库为该状态） */
    public static final String STATUS_PENDING = "PENDING";
    /** 切块向量化中 —— 前端每 3 秒轮询一次列表 */
    public static final String STATUS_PARSING = "PARSING";
    /** 已就绪 —— 前端停止轮询 */
    public static final String STATUS_CHUNKED = "CHUNKED";
    /** 解析失败 —— 前端停止轮询并把 errorMsg 显示为 tooltip */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    @Schema(description = "课件ID")
    private Long id;

    @Schema(description = "关联课程ID")
    private Long courseId;

    @Schema(description = "课件文件名，如：第3章_虚拟内存管理.pdf")
    private String fileName;

    /**
     * 磁盘存储绝对路径。仅服务端内部使用（重建索引时重读磁盘），
     * <b>不下发前端</b>：服务器路径属信息暴露点（历史审查 M 项），
     * {@code @JsonIgnore} 后 {@code /api/teacher/docs/list} 响应不再携带该字段。
     * MyBatis-Plus 读写数据库走 getter/setter，不受影响。
     */
    @JsonIgnore
    @Schema(description = "磁盘存储绝对路径（内部使用，不下发）", hidden = true)
    private String filePath;

    @Schema(description = "文件字节大小")
    private Long fileSize;

    @Schema(description = "文件格式：pdf / docx / md / txt")
    private String fileType;

    @Schema(description = "切块片段总数")
    private Integer chunkCount;

    @Schema(description = "解析状态：PENDING / PARSING / CHUNKED / FAILED")
    private String parseStatus;

    @Schema(description = "解析失败原因（超长会被截断到 500，与列长度一致）")
    private String errorMsg;

    @TableLogic
    @Schema(description = "逻辑删除：0-正常，1-删除")
    private Integer isDeleted;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
