package com.smartqa.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 跨域资源共享 (CORS) 全局配置
 *
 * <p>安全收紧（对抗式审查 M1）：不再对任意来源 {@code "*"} 开放并允许携带凭据。
 * 允许的来源通过 {@code app.cors.allowed-origin-patterns} 配置：</p>
 * <ul>
 *   <li>默认只放行本机前端 dev 端口（教师端 / 学生端 Vite），覆盖 http 与 https、任意端口。</li>
 *   <li>生产环境在 application.yml / 环境变量里显式配置真实前端域名。</li>
 * </ul>
 *
 * <p>说明：Sa-Token 已配 {@code is-read-cookie: false}，令牌只走 Authorization 头、
 * 浏览器不会自动附带，故当前通配凭据的即时可利用性较低；但显式白名单是纵深防御，
 * 避免将来改用 cookie 传令牌时直接暴露。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许跨域访问的前端来源（Ant 风格匹配，支持端口通配）。
     * 默认覆盖本机常见 Vite dev 端口；生产通过配置覆盖为具体域名。
     */
    @Value("${app.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private List<String> allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
