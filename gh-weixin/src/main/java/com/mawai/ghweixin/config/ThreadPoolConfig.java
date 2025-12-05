package com.mawai.ghweixin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class ThreadPoolConfig {

    /**
     * 邮件发送专用虚拟线程执行器
     * 使用虚拟线程处理SMTP邮件发送的IO密集型操作
     * 优势：每个邮件发送请求都能立即获得"线程"，降低发送延迟
     */
    @Bean("wxVirtualExecutor")
    public Executor wxVirtualExecutor() {
        // 邮件发送不需要传递登录信息，直接使用虚拟线程执行器
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
