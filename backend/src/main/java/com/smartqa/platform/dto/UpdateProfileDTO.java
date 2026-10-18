package com.smartqa.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 修改个人资料请求 DTO。
 */
@Data
@Schema(description = "修改个人资料请求")
public class UpdateProfileDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户昵称", requiredMode = Schema.RequiredMode.REQUIRED, example = "小明同学")
    @NotBlank(message = "昵称不能为空")
    @Size(min = 1, max = 30, message = "昵称长度须在 1 到 30 个字符之间")
    private String nickname;

    @Schema(description = "头像地址", example = "/uploads/avatars/abc.png")
    @Size(max = 255, message = "头像地址过长")
    private String avatarUrl;

    @Schema(description = "个性签名", example = "专注操作系统与内核开发")
    @Size(max = 150, message = "个性签名长度不能超过 150 个字符")
    private String bio;
}
