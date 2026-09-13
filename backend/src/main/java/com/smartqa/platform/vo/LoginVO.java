package com.smartqa.platform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录成功返回体。
 *
 * <p>⚠️ 绝不包含 password 字段。</p>
 *
 * @author 成员 B
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录返回")
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "Sa-Token 令牌。前端存入 localStorage，键名 satoken")
    private String token;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "学号 / 工号")
    private String username;

    @Schema(description = "真实姓名")
    private String nickname;

    @Schema(description = "角色：STUDENT / TEACHER。前端据此做路由守卫")
    private String role;

    @Schema(description = "头像地址")
    private String avatarUrl;
}
