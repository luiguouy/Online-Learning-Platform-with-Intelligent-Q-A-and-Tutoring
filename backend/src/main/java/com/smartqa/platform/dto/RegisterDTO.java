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
 * <p>学生注册请求体（官方功能，接口契约见 backend/README-成员B后端代码说明.md §3.1.1）。
 * 注册入口只面向学生：教师账号由 data.sql 预置，任何「给注册加 role 入参」的改动都会打开越权注册教师的口子。
 * 校验规则变更须同步前端 RegisterView.vue 的表单规则与 README §3.1.1。</p>
 *
 * <p>不含 role 字段：自助注册一律落 STUDENT，防止越权注册教师账号。</p>
 *
 * @author 成员 C
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
