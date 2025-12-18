package com.mawai.ghgif.schedule;

import com.mawai.ghmbplus.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通知清理定时任务
 * 每天凌晨3点清理7天前的通知数据
 *
 * @author mawai
 * @since 2025-12-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupScheduler {

    private final NotificationService notificationService;

    /**
     * 清理7天前的通知数据
     * 每天凌晨3点执行
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldNotifications() {
        try {
            LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
            int deletedCount = notificationService.deleteBeforeTime(sevenDaysAgo);
            log.info("通知清理任务执行成功，删除了{}条7天前的通知数据", deletedCount);
        } catch (Exception e) {
            log.error("通知清理任务执行失败: {}", e.getMessage(), e);
        }
    }
}

