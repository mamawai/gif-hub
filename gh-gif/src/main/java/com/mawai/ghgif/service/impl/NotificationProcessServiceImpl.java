package com.mawai.ghgif.service.impl;

import cn.hutool.json.JSONUtil;
import com.mawai.ghaws.constant.MessageType;
import com.mawai.ghaws.service.MessageService;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.service.UserNicknameCacheService;
import com.mawai.ghgif.amazonSQS.message.NotificationMessage;
import com.mawai.ghgif.service.NotificationProcessService;
import com.mawai.ghgif.vo.NotificationVO;
import com.mawai.ghmbplus.model.Notification;
import com.mawai.ghmbplus.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 通知处理服务实现
 *
 * @author mawai
 * @since 2025-12-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationProcessServiceImpl implements NotificationProcessService {

    private final NotificationService notificationService;
    private final MessageService messageService;
    private final CacheService cacheService;
    private final UserNicknameCacheService userNicknameCacheService;

    @Value("${aws.sqs.base-queue-url}")
    private String queueUrl;

    // Redis键前缀
    private static final String UNREAD_COUNT_KEY = "gh:notify:unread:";
    private static final String LIKE_LOCK_KEY = "lock:notify:like:";
    private static final String INIT_LOCK_PREFIX = "lock:notify:init:";
    private static final int LOCK_EXPIRE_MINUTES = 30;
    private static final int UNREAD_COUNT_EXPIRE_MINUTES = 60; // 未读计数1小时过期

    /**
     * 原子递增未读计数（带 DB 回源和并发控制）
     *
     * @param userId 用户ID
     * @param delta 增量（正数增加，负数减少）
     * @return 操作后的计数
     */
    public Long incrementUnreadCount(Long userId, long delta) {
        String key = UNREAD_COUNT_KEY + userId;

        // 1. 如果 key 存在，直接递增并刷新过期时间
        if (cacheService.hasKey(key)) {
            cacheService.increment(key, delta, UNREAD_COUNT_EXPIRE_MINUTES, TimeUnit.MINUTES);
            Number count = cacheService.getNumber(key);
            // 确保不为负数
            if (count != null && count.longValue() < 0) {
                cacheService.set(key, 0L, UNREAD_COUNT_EXPIRE_MINUTES, TimeUnit.MINUTES);
                return 0L;
            }
            return count != null ? count.longValue() : 0L;
        }

        // 2. key 不存在，需要从 DB 初始化（使用分布式锁避免并发问题）
        String lockKey = INIT_LOCK_PREFIX + userId;

        try {
            // 尝试获取锁（SET NX EX） 五秒够用了
            boolean locked = cacheService.setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);
            if (!locked) {
                // 获取锁失败，等待 50ms 后重试
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return incrementUnreadCount(userId, delta);
            }

            try {
                // 从 DB 查询未读数量
                Long dbCount = notificationService.countUnreadByRecipientId(userId);

                // 初始化 Redis（DB 数量 + 当前增量）
                long initialValue = dbCount + delta;
                initialValue = Math.max(0, initialValue); // 不允许负数

                cacheService.set(key, initialValue, UNREAD_COUNT_EXPIRE_MINUTES, TimeUnit.MINUTES);

                log.info("初始化用户{}未读计数: DB={}, delta={}, final={}",
                        userId, dbCount, delta, initialValue);

                return initialValue;

            } finally {
                // 释放锁
                cacheService.delete(lockKey);
            }

        } catch (Exception e) {
            log.error("递增未读计数失败: userId={}, delta={}", userId, delta, e);
            // 降级：返回 -1，避免影响主流程
            return -1L;
        }
    }

    @Override
    public void sendNotification(Long recipientId, Long senderId, Short actionType, Long targetId, String rawContent) {
        // 自我排斥 不发给自己
        if (recipientId.equals(senderId)) return;

        // 30分钟内所有的重复点赞都去重，点赞消息只发送一次不管后续用户是否取消
        String lockKey = LIKE_LOCK_KEY + senderId + ":" + targetId;
        if (cacheService.hasKey(lockKey)) return;

        // 设置去重锁
        cacheService.set(lockKey, "1", LOCK_EXPIRE_MINUTES, TimeUnit.MINUTES);

        // 截取前50个字
        String contentSnapshot = rawContent != null && rawContent.length() > 50
                ? rawContent.substring(0, 50)
                : rawContent;

        // 构建通知消息
        NotificationMessage message = NotificationMessage.builder()
                .recipientId(recipientId)
                .senderId(senderId)
                .actionType(actionType)
                .targetId(targetId)
                .contentSnapshot(contentSnapshot)
                .build();

        // 发送到SQS
        try {
            messageService.send(
                    JSONUtil.toJsonStr(message),
                    queueUrl,
                    MessageType.NOTIFICATION_MESSAGE
            );
            log.info("通知消息发送成功: recipientId={}, senderId={}, actionType={}, targetId={}",
                    recipientId, senderId, actionType, targetId);
        } catch (Exception e) {
            log.error("通知消息发送失败: recipientId={}, senderId={}, actionType={}, targetId={}, error={}",
                    recipientId, senderId, actionType, targetId, e.getMessage(), e);
        }
    }

    @Override
    public void sendGifLikeNotification(Long recipientId, Long senderId, Long gifId, String gifTitle) {
        // Giphy的GIF（userId=0）不发通知
        if (recipientId == 0) return;
        // 发送通知
        sendNotification(recipientId, senderId,(short)1, gifId, gifTitle);
    }

    @Override
    public void sendCommentLikeNotification(Long recipientId, Long senderId, Long commentId, String commentContent) {
        // 发送通知
        sendNotification(recipientId, senderId, (short)2, commentId, commentContent);
    }

    @Override
    public List<NotificationVO> getNotifications(Long userId, int page, int size) {
        // 从数据库查询通知列表
        List<Notification> notifications = notificationService.getNotificationsByRecipientId(userId, page, size);

        if (notifications.isEmpty()) {
            return new ArrayList<>();
        }

        // 收集所有触发者ID
        List<Long> senderIds = notifications.stream()
                .map(Notification::getSenderId)
                .distinct()
                .toList();

        // 批量获取昵称
        Map<Long, String> nicknameMap = userNicknameCacheService.batchGetNicknames(senderIds);

        // 转换为VO并填充昵称
        List<NotificationVO> result = new ArrayList<>();
        for (Notification notification : notifications) {
            NotificationVO vo = new NotificationVO();
            vo.setId(notification.getId());
            vo.setSenderId(notification.getSenderId());
            vo.setActionType(notification.getActionType());
            vo.setTargetId(notification.getTargetId());
            vo.setContentSnapshot(notification.getContentSnapshot());
            vo.setIsRead(notification.getIsRead());
            vo.setCreateTime(notification.getCreateTime());

            // 填充触发者昵称
            String nickname = nicknameMap.get(notification.getSenderId());
            vo.setSenderNickname(nickname != null ? nickname : "未知用户");

            result.add(vo);
        }

        return result;
    }

    @Override
    public Long getUnreadCount(Long userId) {
        String key = UNREAD_COUNT_KEY + userId;
        
        // 如果 Redis 中存在，直接返回并刷新过期时间
        if (cacheService.hasKey(key)) {
            Number count = cacheService.getNumber(key);
            // 刷新过期时间
            cacheService.expire(key, UNREAD_COUNT_EXPIRE_MINUTES, TimeUnit.MINUTES);
            return count != null ? count.longValue() : 0L;
        }
        
        // Redis 中不存在，需要从 DB 初始化（使用分布式锁避免并发问题）
        String lockKey = INIT_LOCK_PREFIX + userId;
        
        try {
            // 尝试获取锁（SET NX EX）
            boolean locked = cacheService.setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);
            if (!locked) {
                // 获取锁失败，等待后重试（递归调用）
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return getUnreadCount(userId);
            }
            
            try {
                // 从 DB 查询并缓存
                Long count = notificationService.countUnreadByRecipientId(userId);
                cacheService.set(key, count, UNREAD_COUNT_EXPIRE_MINUTES, TimeUnit.MINUTES);
                
                log.info("初始化用户{}未读计数: DB={}", userId, count);
                
                return count;
                
            } finally {
                // 释放锁
                cacheService.delete(lockKey);
            }
            
        } catch (Exception e) {
            log.error("获取未读计数失败: userId={}", userId, e);
            return -1L;
        }
    }

    @Override
    public boolean clearUnread(Long userId, LocalDateTime clearTime) {
        try {
            // 1. 标记此时间点之前的通知为已读
            int updatedCount = notificationService.markAllAsRead(userId, clearTime);
            
            // 2. 递减未读计数（使用新方法）
            if (updatedCount > 0) {
                Long newCount = incrementUnreadCount(userId, -updatedCount);
                if (newCount >= 0) {
                log.info("清除用户{}在{}之前的{}条未读通知，剩余未读: {}",
                        userId, clearTime, updatedCount, newCount);
                }
            }

            return true;
        } catch (Exception e) {
            log.error("清除用户{}的未读通知失败: {}", userId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean clearOne(Long notificationId, Long userId) {
        try {
            // 1. 标记为已读，返回更新的行数（0或1）
            int updatedCount = notificationService.markOneAsRead(notificationId);
            
            // 2. 如果更新成功（返回1），递减未读计数（使用新方法）
            if (updatedCount > 0) {
                Long newCount = incrementUnreadCount(userId, -1);
                if (newCount >= 0) {
                log.info("标记通知{}为已读，用户{}剩余未读: {}", notificationId, userId, newCount);
                }
                return true;
            }
            
            return false;
        } catch (Exception e) {
            log.error("标记通知{}为已读失败: {}", notificationId, e.getMessage(), e);
            return false;
        }
    }
}
