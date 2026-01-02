package com.mawai.ghgif.config;

import com.mawai.ghcommon.utils.AsyncContextHolder;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置类
 */
@Slf4j
@Configuration
@EnableAsync
public class ThreadPoolConfig {

    /**
     * 定时任务专用线程池
     *
     * <p>配置说明（已优化）：</p>
     * <ul>
     *   <li>核心线程数 2：每组定时任务最多 2 个并发子任务</li>
     *   <li>最大线程数 6：极端情况下允许 3 组任务同时执行（每组 2 个线程）</li>
     *   <li>队列容量 4：允许 2 组任务排队（每组 2 个任务）</li>
     *   <li>拒绝策略 CallerRunsPolicy：超过容量时由调度线程执行，起到背压作用</li>
     *   <li>允许核心线程超时：空闲时自动回收，节省内存</li>
     * </ul>
     *
     * <p><b>优化效果：</b></p>
     * <ul>
     *   <li>内存占用：从 12 线程 × 1MB ≈ 12MB → 6 线程 × 1MB ≈ 6MB（节省 50%）</li>
     *   <li>任务分组：3 组错开执行（9min、10min、11min），降低瞬时峰值</li>
     *   <li>正常情况：只使用 2 个核心线程，空闲后自动回收</li>
     * </ul>
     */
    @Bean("scheduledExecutor")
    public Executor scheduledExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：2（每组定时任务最多 2 个并发子任务）
        executor.setCorePoolSize(2);
        // 最大线程数：6（极端情况下 3 组同时执行，每组 2 个线程）
        executor.setMaxPoolSize(6);
        // 队列容量：4（允许 2 组任务排队，每组 2 个任务）
        executor.setQueueCapacity(4);
        // 线程名前缀
        executor.setThreadNamePrefix("scheduled-");
        // 线程空闲时间：60 秒后回收
        executor.setKeepAliveSeconds(60);
        // 允许核心线程超时回收（节省内存）
        executor.setAllowCoreThreadTimeOut(true);
        // 拒绝策略：由调用线程处理（如果真的堆积，让调度线程自己执行）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 等待时间
        executor.setAwaitTerminationSeconds(60);
        // 初始化
        executor.initialize();
        return executor;
    }

    /**
     * 文件上传专用虚拟线程执行器
     * 使用虚拟线程处理文件上传到Cloudflare R2的IO密集型操作
     * 优势：支持更高并发、更低内存占用、无需复杂的线程池配置
     */
    @Bean("fileUploadExecutor")
    public Executor fileUploadExecutor() {
        // 创建虚拟线程执行器，每个任务都会创建一个新的虚拟线程
        return new VirtualThreadTaskExecutor();
    }

    /**
     * 虚拟线程任务执行器，支持TaskDecorator
     * 用于在虚拟线程中传递登录信息等上下文
     */
    public static class VirtualThreadTaskExecutor implements Executor {
        private final AsyncContextHolder.CustomTaskDecorator taskDecorator = new AsyncContextHolder.CustomTaskDecorator();

        @Override
        public void execute(@NonNull Runnable command) {
            Runnable decoratedTask = taskDecorator.decorate(command);
            Thread.ofVirtual()
                .name("virtual-file-upload-", 0)
                .start(decoratedTask);
        }
    }
    
//    /**
//     * 通用异步任务线程池
//     * 核心线程数1，最多5个线程，队列100，空闲60秒回收
//     */
//    @Bean("taskExecutor")
//    public Executor taskExecutor() {
//        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
//        // 核心线程数：1（平时只维护1个活跃线程）
//        executor.setCorePoolSize(1);
//        // 最大线程数：5（最多创建5个线程）
//        executor.setMaxPoolSize(5);
//        // 队列容量：100（任务入队）
//        executor.setQueueCapacity(100);
//        // 线程名前缀
//        executor.setThreadNamePrefix("async-task-");
//        // 线程空闲时间：60秒
//        executor.setKeepAliveSeconds(60);
//        // 允许核心线程超时回收
//        executor.setAllowCoreThreadTimeOut(true);
//        // 拒绝策略：由调用线程处理
//        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
//        // 等待所有任务结束后再关闭线程池
//        executor.setWaitForTasksToCompleteOnShutdown(true);
//        // 等待时间
//        executor.setAwaitTerminationSeconds(60);
//        // 初始化
//        executor.initialize();
//        return executor;
//    }
} 