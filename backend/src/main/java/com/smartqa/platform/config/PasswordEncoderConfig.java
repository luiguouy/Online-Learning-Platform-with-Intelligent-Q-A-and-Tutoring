package com.smartqa.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置。
 *
 * <p>为什么抽成 Bean 而不是在 Controller 里内联 {@code new}：
 * 登录校验需要的是「校验密码」这一能力，而不是某个具体实现。内联 new 会让
 * 将来调整强度（strength）、更换算法（如 Argon2）或替换为测试桩时，
 * 只能满仓库搜 {@code new}，也违背「Controller 不写业务逻辑」的分层约定。</p>
 *
 * <p>注意：本项目未引入 {@code spring-boot-starter-security}，只用到
 * {@code spring-security-crypto}，本 Bean 不会触发 Spring Security 的
 * 自动配置（不会导致接口被默认 HTTP Basic 保护）。</p>
 *
 * @author 成员 B
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
