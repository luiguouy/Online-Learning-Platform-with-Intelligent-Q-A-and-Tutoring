package com.smartqa.platform.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 核心配置类
 *
 * <p>@MapperScan 必须同时覆盖成员 B 的 {@code mapper} 包与
 * 成员 A 预留的 {@code dao} 包：只扫一个会导致另一侧的 Mapper
 * 在运行时注入失败（NoSuchBeanDefinitionException）。</p>
 */
@Configuration
@MapperScan({"com.smartqa.platform.dao", "com.smartqa.platform.mapper"})
public class MyBatisPlusConfig {

    /**
     * 分页插件拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 添加 MySQL 分页拦截器
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大 100 条，防止前端传入超大 size 拖垮数据库
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
