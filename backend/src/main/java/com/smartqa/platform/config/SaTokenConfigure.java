package com.smartqa.platform.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.interceptor.QaRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由鉴权拦截器配置。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>{@code /api/teacher/**} → 必须具备 TEACHER 角色</li>
 *   <li>{@code /api/qa/**} → 必须已登录</li>
 *   <li>放行：登录接口与接口文档</li>
 * </ul>
 *
 * <p>⚠️ 本期无注册接口，不要添加 /api/auth/register 白名单。</p>
 *
 * @author 成员 B
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfigure implements WebMvcConfigurer {

    private final QaRateLimitInterceptor qaRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 登录与角色拦截
        registry.addInterceptor(new SaInterceptor(handle -> {
                    SaRouter.match("/api/teacher/**", r -> StpUtil.checkRole("TEACHER"));
                    SaRouter.match("/api/qa/**", r -> StpUtil.checkLogin());
                }))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/doc.html",
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html"
                );

        // 2. 答疑 / 知识点接口限流。
        //    只拦截这两个接口，全局拦截会误伤登录与课件上传。
        registry.addInterceptor(qaRateLimitInterceptor)
                .addPathPatterns("/api/qa/chat/stream", "/api/knowledge/generate");
    }
}
