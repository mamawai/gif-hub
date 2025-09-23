package com.mawai.ghgif.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 虚拟线程监控组件
 * 用于监控和验证虚拟线程的使用情况
 */
@Slf4j
@Component
public class VirtualThreadMonitor {

    /**
     * 应用启动完成后验证虚拟线程配置
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("=== 虚拟线程配置验证 ===");
        
        // 检查JDK版本
        String javaVersion = System.getProperty("java.version");
        log.info("当前JDK版本: {}", javaVersion);
        
        // 验证虚拟线程是否可用
        try {
            Thread virtualThread = Thread.ofVirtual()
                .name("test-virtual-thread")
                .start(() -> {
                    log.info("虚拟线程测试成功! 当前线程: {}", Thread.currentThread());
                });
            
            virtualThread.join(); // 等待虚拟线程完成
            log.info("✅ 虚拟线程功能正常");
            
        } catch (Exception e) {
            log.error("❌ 虚拟线程测试失败: {}", e.getMessage(), e);
        }
    }
}
