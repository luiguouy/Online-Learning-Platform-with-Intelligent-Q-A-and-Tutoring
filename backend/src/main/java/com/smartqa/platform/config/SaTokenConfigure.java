package com.smartqa.platform.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.interceptor.AuthRateLimitInterceptor;
import com.smartqa.platform.interceptor.QaRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 鉴权与路由拦截器配置
 *
 * <p>除登录/角色鉴权外，还注册了两个限流拦截器：</p>
 * <ul>
 *   <li>{@link QaRateLimitInterceptor}（成员 B）：只拦消耗大模型 Token 的两个问答接口，
 *       不做全局拦截（全局会误伤登录与课件上传）；</li>
 *   <li>{@link AuthRateLimitInterceptor}（成员 A）：拦截匿名入口（登录/注册）与写盘接口（课件上传），
 *       详见类注释中的阈值口径与键选择依据。</li>
 * </ul>
 *
 * <p><b>关于鉴权白名单</b>：{@code /api/auth/register} 只服务学生（服务端固定落 STUDENT、
 * 不接受 role 入参），因此可匿名放行；{@code /doc.html} 与 {@code /v3/api-docs/**} 属于接口文档，
 * <b>仅开发环境可用</b>，生产必须激活 {@code prod} profile 关闭（见 application-prod.yml）。</p>
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfigure implements WebMvcConfigurer {

    private final QaRateLimitInterceptor qaRateLimitInterceptor;

    private final AuthRateLimitInterceptor authRateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 默认拒绝：/api/** 下除下方白名单外一律要求已登录。
            // 之前只逐条 match /api/qa/**、/api/knowledge/**，新接口一旦漏写 match 就会裸奔；
            // 改成“默认登录 + 显式放行”，以后新增任何 /api/** 接口自动纳入鉴权。
            SaRouter.match("/api/**", r -> StpUtil.checkLogin());
            // 教师后台接口在“已登录”基础上再要求 TEACHER 角色
            SaRouter.match("/api/teacher/**", r -> StpUtil.checkRole("TEACHER"));
        })).addPathPatterns("/api/**")
           // 放行登录、注册与 Swagger/Knife4j 接口文档静态资源
           // /api/auth/register 只服务学生：服务端固定落 STUDENT、不接受 role 入参，故可公开放行；教师端无注册入口
           .excludePathPatterns(
                   "/api/auth/login",
                   "/api/auth/register",
                   "/doc.html",
                   "/v3/api-docs/**",
                   "/webjars/**",
                   "/swagger-ui/**",
                   "/favicon.ico"
           );

        // 答疑 / 知识点生成接口限流：每用户每 60 秒最多 20 次，超限返回 429
        registry.addInterceptor(qaRateLimitInterceptor)
                .addPathPatterns("/api/qa/chat/stream", "/api/knowledge/generate");

        // 匿名入口与写盘接口限流（防登录暴破 / 批量灌水注册 / 刷盘）：
        // 必须排在 Sa-Token 拦截器之后注册：登录用户类规则靠前的鉴权已保证已登录。
        // 学生附件与头像接口（/api/qa/upload/attachment、/api/user/avatar）尚未合入 dev，
        // 落地时同步往 AuthRateLimitInterceptor.RULES 里补两条同等阈值的规则即可生效。
        registry.addInterceptor(authRateLimitInterceptor)
                .addPathPatterns("/api/auth/login", "/api/auth/register", "/api/teacher/docs/upload");
    }

    @org.springframework.beans.factory.annotation.Value("${file.upload-dir}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        String cleanPath = uploadDir.replace("\\", "/");
        if (!cleanPath.endsWith("/")) {
            cleanPath += "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + cleanPath);
    }
}
