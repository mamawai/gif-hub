package com.mawai.ghgif.service;

import com.mawai.ghgif.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * WebSocket通知推送服务
 * 负责向在线用户实时推送通知
 *
 * @author mawai
 * @since 2025-12-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 向指定用户推送通知
     *
     * @param userId 用户ID
     * @param notification 通知内容
     */
    public void sendNotificationToUser(Long userId, NotificationVO notification) {
        try {
            // 发送到 /user/{userId}/queue/notifications
            // 前端订阅: /user/queue/notifications
            messagingTemplate.convertAndSendToUser(
                    userId.toString(),
                    "/queue/notifications",
                    notification
            );
            log.info("WebSocket推送通知成功,用户ID: {}, 通知类型: {}", userId, notification.getActionType());
        } catch (Exception e) {
            // WebSocket推送失败不影响业务,仅记录日志
            log.warn("WebSocket推送通知失败,用户ID: {}, 原因: {}", userId, e.getMessage());
        }
    }

}

