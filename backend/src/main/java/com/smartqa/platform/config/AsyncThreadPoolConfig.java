package com.smartqa.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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
        // 队列满且线程达上限时，由提交任务的线程（如 Tomcat 工作线程）直接执行，
        // 用背压替代静默丢弃，避免前端长连接永远收不到事件
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭：先处理完在途的 SSE / 切块任务再退出，防止切块跑到一半状态卡在 PARSING
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
