package com.mawai.ghgif.config;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
     * <p>配置说明：</p>
     * <ul>
     *   <li>核心线程数 6：对应 syncGifLikeCount() 中的 6 个并发任务</li>
     *   <li>最大线程数 12：允许 2 轮任务同时执行（应对任务延迟）</li>
     *   <li>队列容量 6：只允许 1 轮任务排队，避免无限堆积</li>
     *   <li>拒绝策略 CallerRunsPolicy：超过容量时由调度线程执行，起到背压作用</li>
     * </ul>
     */
    @Bean("scheduledExecutor")
    public Executor scheduledExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：6（对应6个并发任务）
        executor.setCorePoolSize(6);
        // 最大线程数：12（允许2轮任务同时执行）
        executor.setMaxPoolSize(12);
        // 队列容量：6（只允许1轮任务排队）
        executor.setQueueCapacity(6);
        // 线程名前缀
        executor.setThreadNamePrefix("scheduled-");
        // 线程空闲时间
        executor.setKeepAliveSeconds(60);
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
        private final TaskDecorator taskDecorator = new CustomTaskDecorator();

        @Override
        public void execute(@NonNull Runnable command) {
            // 应用任务装饰器，传递登录信息
            Runnable decoratedTask = taskDecorator.decorate(command);
            // 在虚拟线程中执行任务
            Thread.ofVirtual()
                .name("virtual-file-upload-", 0)
                .start(decoratedTask);
        }
    }

    /**
     * 用于在异步线程中传递登录信息的ThreadLocal
     */
    private static final ThreadLocal<String> ASYNC_LOGIN_ID = new ThreadLocal<>();

    /**
     * 自定义任务装饰器，用于传递登录信息到异步线程
     */
    public static class CustomTaskDecorator implements TaskDecorator {
        @Override
        @NonNull
        public Runnable decorate(@NonNull Runnable runnable) {
            // 在主线程中获取当前登录信息和请求上下文
            String loginId = null;
            try {
                // 获取当前登录的用户ID和token
                if (StpUtil.isLogin()) {
                    loginId = StpUtil.getLoginIdAsString();
                }
            } catch (Exception e) {
                // 如果获取失败，可能是因为当前线程没有登录信息或请求上下文（也就是不需要上下文），忽略即可
                log.warn("CustomTaskDecorator: 获取主线程上下文失败", e);
            }
            final String finalLoginId = loginId;
            return () -> {
                try {
                    // 在异步线程中设置登录信息到ThreadLocal
                    if (finalLoginId != null) ASYNC_LOGIN_ID.set(finalLoginId);
                    // 执行原始任务
                    runnable.run();
                } finally {
                    // 清理当前线程的上下文信息
                    try {
                        ASYNC_LOGIN_ID.remove();
                    } catch (Exception e) {
                        log.warn("CustomTaskDecorator: 清理子线程上下文信息失败", e);
                    }
                }
            };
        }
        
        /**
         * 在异步线程中获取登录ID
         * @return 登录用户ID，如果未设置则返回null
         */
        public static String getAsyncLoginId() {
            return ASYNC_LOGIN_ID.get();
        }
    }
    
    /**
     * 通用异步任务线程池
     */
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：CPU核心数
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors());
        // 最大线程数：CPU核心数的2倍
        executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 2);
        // 队列容量
        executor.setQueueCapacity(50);
        // 线程名前缀
        executor.setThreadNamePrefix("task-");
        // 拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 初始化
        executor.initialize();
        return executor;
    }

    /**
     * 邮件发送专用虚拟线程执行器
     * 使用虚拟线程处理SMTP邮件发送的IO密集型操作
     * 优势：每个邮件发送请求都能立即获得"线程"，降低发送延迟
     */
    @Bean("emailSendExecutor")
    public Executor emailSendExecutor() {
        // 邮件发送不需要传递登录信息，直接使用虚拟线程执行器
        return Executors.newVirtualThreadPerTaskExecutor();
    }
} 