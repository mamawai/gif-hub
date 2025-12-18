package com.mawai.ghgif.service;

import com.mawai.ghgif.vo.NotificationVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知处理服务接口
 *
 * @author mawai
 * @since 2025-12-15
 */
public interface NotificationProcessService {

    /**
     * 递增未读通知数量
     *
     * @param userId 用户ID
     * @param delta 增量
     * @return 新的未读数量
     */
    Long incrementUnreadCount(Long userId, long delta);

    /**
     * 发送通知（异步SQS）
     *
     * @param recipientId 接收者ID
     * @param senderId 触发者ID
     * @param actionType 动作类型：1-点赞GIF, 2-点赞评论, 3-评论GIF, 4-回复评论
     * @param targetId 关联的目标ID
     * @param contentSnapshot 内容快照
     */
    void sendNotification(Long recipientId, Long senderId, Short actionType, Long targetId, String contentSnapshot);

    /**
     * 发送点赞GIF通知（带30分钟去重锁）
     * 
     * @param recipientId 接收者ID（GIF作者）
     * @param senderId 触发者ID（点赞用户）
     * @param gifId GIF ID
     * @param gifTitle GIF标题
     */
    void sendGifLikeNotification(Long recipientId, Long senderId, Long gifId, String gifTitle);

    /**
     * 发送点赞评论通知（带30分钟去重锁）
     * 
     * @param recipientId 接收者ID（评论作者）
     * @param senderId 触发者ID（点赞用户）
     * @param commentId 评论ID
     * @param commentContent 评论内容
     */
    void sendCommentLikeNotification(Long recipientId, Long senderId, Long commentId, String commentContent);

    /**
     * 获取用户的通知列表
     * 
     * @param userId 用户ID
     * @param page 页码
     * @param size 每页数量
     * @return 通知列表
     */
    List<NotificationVO> getNotifications(Long userId, int page, int size);

    /**
     * 获取用户的未读通知数量
     * 
     * @param userId 用户ID
     * @return 未读数量
     */
    Long getUnreadCount(Long userId);

    /**
     * 清除用户的未读通知
     *
     * @param userId 用户ID
     * @param clearTime 清除时间点（在Controller层生成，尽早记录时间）
     * @return 是否成功
     */
    boolean clearUnread(Long userId, LocalDateTime clearTime);

    /**
     * 清除单个通知（标记为已读）
     *
     * @param notificationId 通知ID
     * @param userId 用户ID（用于递减Redis计数）
     * @return 是否成功
     */
    boolean clearOne(Long notificationId, Long userId);
}

