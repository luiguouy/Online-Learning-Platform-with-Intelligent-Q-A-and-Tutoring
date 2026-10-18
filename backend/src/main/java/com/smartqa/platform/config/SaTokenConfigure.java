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
        // 路径清单必须与 AuthRateLimitInterceptor.RULES 逐项对应（SEC-3 硬约束 4：只改一边等于死配置或不拦）。
        registry.addInterceptor(authRateLimitInterceptor)
                .addPathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/teacher/docs/upload",
                        "/api/qa/upload/attachment",
                        "/api/user/avatar");
    }

    @org.springframework.beans.factory.annotation.Value("${file.upload-dir}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        String cleanPath = uploadDir.replace("\\", "/");
        if (!cleanPath.endsWith("/")) {
            cleanPath += "/";
        }
        // SEC-2（dev-docs/SECURITY_DECISIONS.md）：**严禁**把上传根目录整体映射为静态资源（"/uploads/**" 这种盖头写法）。
        // Sa-Token 只 match("/api/**")，走到这里的路径等于完全不要登录。而课件原件落在
        // {uploadDir}/{courseId}/{时间戳}_{原始文件名}（零随机熵、courseId 自增、文件名在学生端可见），
        // 盖头映射会把它们变成“未登录可下载”——而 SEC-1 的口径是“对全体**登录**用户公开”，不是匿名。
        // 只允许下这两个低敏子目录；新增公开目录时必须逐条往这里加，同时保证文件名带完整 UUID。
        registry.addResourceHandler("/uploads/avatars/**")
                .addResourceLocations("file:" + cleanPath + "avatars/");
        registry.addResourceHandler("/uploads/attachments/**")
                .addResourceLocations("file:" + cleanPath + "attachments/");
        // 课件原件如需“下载原件”能力，走带鉴权的接口（TEACHER 角色 + assertTeacherOwnsCourse），
        // 不得靠静态映射兼带发出。
    }
}
