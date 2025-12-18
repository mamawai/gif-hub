package com.mawai.ghmbplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.ghmbplus.model.Notification;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知表 Service 接口
 *
 * @author mawai
 * @since 2025-12-15
 */
public interface NotificationService extends IService<Notification> {

    /**
     * 分页查询用户的通知列表
     *
     * @param recipientId 接收者ID
     * @param page 页码（从1开始）
     * @param size 每页数量
     * @return 通知列表
     */
    List<Notification> getNotificationsByRecipientId(Long recipientId, int page, int size);

    /**
     * 标记指定时间之前的未读通知为已读
     *
     * @param recipientId 接收者ID
     * @param beforeTime 时间阈值
     * @return 更新的行数
     */
    int markAllAsRead(Long recipientId, LocalDateTime beforeTime);

    /**
     * 删除指定时间之前的通知
     *
     * @param beforeTime 时间阈值
     * @return 删除的行数
     */
    int deleteBeforeTime(LocalDateTime beforeTime);

    /**
     * 标记单个通知为已读
     *
     * @param notificationId 通知ID
     * @return 更新的行数（0或1）
     */
    int markOneAsRead(Long notificationId);

    /**
     * 统计用户的未读通知数量
     *
     * @param recipientId 接收者ID
     * @return 未读数量
     */
    Long countUnreadByRecipientId(Long recipientId);
}

