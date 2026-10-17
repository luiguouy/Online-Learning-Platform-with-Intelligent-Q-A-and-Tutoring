package com.smartqa.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 注册请求体。
 *
 * <p>⚠️ 注意：本期范围冻结、官方无注册功能（DEV_SPECIFICATION.md「本期不实现注册」）。
 * 本 DTO 仅存在于成员 C 的本地分支 {@code feature/c-register}，用于本地演示/学习，
 * 未经组长更新规范前不得合并到 dev。</p>
 *
 * <p>不含 role 字段：自助注册一律落 STUDENT，防止越权注册教师账号。</p>
 *
 * @author 成员 C（本地扩展）
 */
@Data
@Schema(description = "注册请求")
public class RegisterDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "账号不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{4,20}$", message = "账号须为 4-20 位字母、数字或下划线")
    @Schema(description = "登录学号", example = "student03", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度须为 6-32 位")
    @Schema(description = "密码（明文传输，服务端 BCrypt 加密落库）", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @NotBlank(message = "姓名不能为空")
    @Size(max = 50, message = "姓名最长 50 个字符")
    @Schema(description = "用户真实姓名", example = "李小华", requiredMode = Schema.RequiredMode.REQUIRED)
    private String nickname;
}
