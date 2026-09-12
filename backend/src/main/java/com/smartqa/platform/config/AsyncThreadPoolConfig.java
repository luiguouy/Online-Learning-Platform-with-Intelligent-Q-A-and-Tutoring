package com.smartqa.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步与 SSE 专属线程池配置
 * 专供 SSE 流式响应与课件异步切块，严禁与普通业务接口混用
 */
@Configuration
@EnableAsync
public class AsyncThreadPoolConfig {

    @Bean(name = "sseExecutor")
    public Executor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("sse-worker-");
        executor.initialize();
        return executor;
    }
}
