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
 * Sa-Token 鉴权与路由拦截器配置
 *
 * <p>除登录/角色鉴权外，还注册了成员 B 的答疑接口限流拦截器
 * {@link QaRateLimitInterceptor}：该拦截器只拦截消耗大模型 Token 的
 * 两个接口，不做全局拦截（全局会误伤登录与课件上传）。</p>
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfigure implements WebMvcConfigurer {

    private final QaRateLimitInterceptor qaRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 教师后台接口必须具备 TEACHER 角色
            SaRouter.match("/api/teacher/**", r -> StpUtil.checkRole("TEACHER"));
            // 学生问答及知识点生成接口必须已登录
            SaRouter.match("/api/qa/**", r -> StpUtil.checkLogin());
            SaRouter.match("/api/knowledge/**", r -> StpUtil.checkLogin());
        })).addPathPatterns("/api/**")
           // 放行登录与 Swagger/Knife4j 接口文档静态资源
           .excludePathPatterns(
                   "/api/auth/login",
                   "/doc.html",
                   "/v3/api-docs/**",
                   "/webjars/**",
                   "/swagger-ui/**",
                   "/favicon.ico"
           );

        // 答疑 / 知识点生成接口限流：每用户每 60 秒最多 20 次，超限返回 429
        registry.addInterceptor(qaRateLimitInterceptor)
                .addPathPatterns("/api/qa/chat/stream", "/api/knowledge/generate");
    }
}
