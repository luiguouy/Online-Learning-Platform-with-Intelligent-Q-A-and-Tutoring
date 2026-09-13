package com.smartqa.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * SSE 推流 / 课件切块专用线程池。
 *
 * <p>硬性规范（AGENT_INSTRUCTIONS 1.3）：</p>
 * <ul>
 *   <li>Bean 名称固定为 {@code sseExecutor}，成员 A/B 均以
 *       {@code @Resource(name = "sseExecutor")} 按名注入。</li>
 *   <li>严禁使用默认公共线程池（ForkJoinPool / SimpleAsyncTaskExecutor）。</li>
 *   <li>该池只服务 SSE 推流与课件异步切块，业务接口不得复用，
 *       防止大模型长耗时任务饿死普通请求。</li>
 * </ul>
 *
 * @author 成员 B
 */
@Configuration
public class AsyncThreadPoolConfig {

    @Bean(name = "sseExecutor")
    public Executor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("sse-worker-");
        // 队列满时的兜底策略：由调用线程执行，保证课件切块任务不被静默丢弃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 应用关闭时等待在途任务收尾，避免切块写库写一半
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
