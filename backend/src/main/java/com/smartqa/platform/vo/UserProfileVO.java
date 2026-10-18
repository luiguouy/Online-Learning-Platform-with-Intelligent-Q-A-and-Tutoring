package com.smartqa.platform.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户主页个人资料展示 VO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户个人资料")
public class UserProfileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户 ID")
    private Long userId;

    @Schema(description = "学号 / 工号")
    private String username;

    @Schema(description = "用户昵称 / 姓名")
    private String nickname;

    @Schema(description = "用户角色：STUDENT / TEACHER")
    private String role;

    @Schema(description = "头像访问地址")
    private String avatarUrl;

    @Schema(description = "个性签名")
    private String bio;

    @Schema(description = "注册 / 加入时间")
    private LocalDateTime createdAt;

    @Schema(description = "累计发起的提问会话数")
    private Long sessionCount;

    @Schema(description = "累计互动问答条数")
    private Long recordCount;
}
