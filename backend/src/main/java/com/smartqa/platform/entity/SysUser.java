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
 * 系统用户实体（对应表 {@code sys_user}）。
 *
 * <p>⚠️ 严禁把本实体直接返回给前端（含 password）。登录接口只返回
 * {@link com.smartqa.platform.vo.LoginVO}。</p>
 *
 * @author 成员 B
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user")
@Schema(description = "系统用户")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "登录学号 / 工号")
    private String username;

    @Schema(description = "BCrypt 加密密码")
    private String password;

    @Schema(description = "用户真实姓名")
    private String nickname;

    @Schema(description = "角色：STUDENT-学生 / TEACHER-教师")
    private String role;

    @Schema(description = "头像地址")
    private String avatarUrl;

    /** 逻辑删除列：6 张表全部必须有，缺列时 removeById() 会抛 Unknown column */
    @TableLogic
    @Schema(description = "逻辑删除：0-正常，1-删除")
    private Integer isDeleted;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
