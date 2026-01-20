package com.mawai.wiibservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 虚拟线程配置
 * 为异步任务和定时任务提供虚拟线程支持
 *
 * <p>线程池分类：</p>
 * <ul>
 *   <li>asyncExecutor - 虚拟线程池，用于I/O密集型异步任务</li>
 *   <li>transactionalExecutor - 平台线程池，用于需要事务的操作</li>
 *   <li>scheduledExecutor - 虚拟线程池，用于定时任务</li>
 * </ul>
 *
 * <p>虚拟线程优势：</p>
 * <ul>
 *   <li>轻量级：每个虚拟线程只占用几KB内存</li>
 *   <li>高并发：支持数万并发任务</li>
 *   <li>简化代码：使用同步风格编写异步代码</li>
 * </ul>
 *
 * <p>注意事项：</p>
 * <ul>
 *   <li>虚拟线程不适合CPU密集型任务</li>
 *   <li>事务操作应使用transactionalExecutor</li>
 *   <li>虚拟线程会自动pin到平台线程执行synchronized代码块</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class VirtualThreadConfig {

    /** 虚拟线程最大并发数量限制 */
    private static final int MAX_VIRTUAL_THREADS = 10000;

    /** 虚拟线程信号量，用于限流 */
    private static final Semaphore VIRTUAL_THREAD_LIMITER = new Semaphore(MAX_VIRTUAL_THREADS);

    /**
     * 异步任务执行器（虚拟线程）
     * 用于@Async注解的I/O密集型方法
     */
    @Bean(name = "asyncExecutor")
    public Executor asyncExecutor() {
        var virtualThreadFactory = Thread.ofVirtual()
                .name("async-", 0)
                .factory();

        var executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭异步任务执行器");
            executor.shutdown();
        }, "async-executor-shutdown"));

        log.info("虚拟线程异步执行器已启动，最大并发: {}", MAX_VIRTUAL_THREADS);
        return executor;
    }

    /**
     * 事务任务执行器（平台线程）
     * 用于需要数据库事务的异步操作
     * 平台线程保证ThreadLocal正确传播（如事务上下文、Sa-Token认证信息）
     */
    @Bean(name = "transactionalExecutor")
    public Executor transactionalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("tx-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("平台线程事务执行器已启动，核心线程: {}, 最大线程: {}", 10, 50);
        return executor;
    }

    /**
     * 定时任务执行器（虚拟线程）
     * 用于@Scheduled注解的方法
     */
    @Bean(name = "scheduledExecutor")
    public Executor scheduledExecutor() {
        var virtualThreadFactory = Thread.ofVirtual()
                .name("scheduled-", 0)
                .factory();

        var executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭定时任务执行器");
            executor.shutdown();
        }, "scheduled-executor-shutdown"));

        log.info("虚拟线程定时任务执行器已启动");
        return executor;
    }

    /**
     * 执行带限流的虚拟线程任务
     * 防止创建过多虚拟线程导致资源耗尽
     *
     * @param task 要执行的任务
     */
    public static void executeWithLimit(Runnable task) {
        try {
            VIRTUAL_THREAD_LIMITER.acquire();
            Thread.startVirtualThread(() -> {
                try {
                    task.run();
                } finally {
                    VIRTUAL_THREAD_LIMITER.release();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取虚拟线程许可被中断");
        }
    }

    /**
     * 尝试执行带限流的虚拟线程任务（非阻塞）
     *
     * @param task 要执行的任务
     * @return 是否成功提交任务
     */
    public static boolean tryExecuteWithLimit(Runnable task) {
        if (!VIRTUAL_THREAD_LIMITER.tryAcquire()) {
            log.warn("虚拟线程数量已达上限: {}", MAX_VIRTUAL_THREADS);
            return false;
        }

        Thread.startVirtualThread(() -> {
            try {
                task.run();
            } finally {
                VIRTUAL_THREAD_LIMITER.release();
            }
        });
        return true;
    }

    /**
     * 获取当前可用的虚拟线程许可数
     */
    public static int getAvailablePermits() {
        return VIRTUAL_THREAD_LIMITER.availablePermits();
    }
}
