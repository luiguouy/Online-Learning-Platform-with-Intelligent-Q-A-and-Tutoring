package com.smartqa.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与 SSE 专属线程池配置
 *
 * <p>拆成两个相互隔离的池，避免「CPU 密集的课件切块」与「长时间挂着的 SSE 流」
 * 互相争抢、互相饿死：</p>
 * <ul>
 *   <li>{@code sseExecutor}：只跑 SSE 流式问答（单次可挂 120s）。</li>
 *   <li>{@code ingestExecutor}：只跑课件 Tika 切块 + 向量化（CPU/内存尖峰）。</li>
 * </ul>
 *
 * <p>两个池均采用 {@link ThreadPoolExecutor.AbortPolicy}：满了直接拒绝而不是
 * CallerRunsPolicy。CallerRuns 会把任务回落到提交线程（Tomcat HTTP 线程）执行，
 * 一两个慢流就能把 Tomcat 线程池拖空 → 全站拒绝服务。宁可快速失败返回“服务繁忙”</p>
 */
@Configuration
@EnableAsync
public class AsyncThreadPoolConfig {

    /** SSE 流式问答专属池 */
    @Bean(name = "sseExecutor")
    public Executor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("sse-worker-");
        // 队满且线程达上限时直接拒绝（由 Controller 捕获后下发 error 事件），
        // 绝不回落占用 Tomcat 工作线程。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** 课件异步切块向量化专属池（与问答流隔离） */
    @Bean(name = "ingestExecutor")
    public Executor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-worker-");
        // 同样用 Abort：拒绝时由上传/重建接口捕获并把课件置为 FAILED，不阻塞请求线程。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
