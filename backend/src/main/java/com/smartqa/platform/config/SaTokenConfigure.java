package com.smartqa.platform.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 鉴权与路由拦截器配置
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

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
                   "/favicon.ico"
           );
    }
}
