package com.smartqa.platform.dto;

import com.smartqa.platform.validation.ValidNickname;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 修改个人资料请求 DTO。
 *
 * <p>昵称与注册时的「姓名」写的是同一列 {@code sys_user.nickname}，因此两处共用
 * {@link ValidNickname}（1-30 字 + 结构卫生），不得一边 30 一边 50。</p>
 *
 * <p>{@code bio} 是多行文本，允许换行，因而不能复用昵称约束（控制字符规则会连 \n 一起拒），
 * 本 DTO 对其只限长度；若将来要加同样的字符卫生规则，需单独写约束并允许换行。</p>
 */
@Data
@Schema(description = "修改个人资料请求")
public class UpdateProfileDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户昵称（与注册时的姓名同一约束）", requiredMode = Schema.RequiredMode.REQUIRED, example = "小明同学")
    @NotBlank(message = "昵称不能为空")
    @ValidNickname
    private String nickname;

    @Schema(description = "头像地址", example = "/uploads/avatars/abc.png")
    @Size(max = 255, message = "头像地址过长")
    private String avatarUrl;

    @Schema(description = "个性签名", example = "专注操作系统与内核开发")
    @Size(max = 150, message = "个性签名长度不能超过 150 个字符")
    private String bio;
}
