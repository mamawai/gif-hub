package com.mawai.ghmbplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.ghmbplus.dao.NotificationMapper;
import com.mawai.ghmbplus.model.Notification;
import com.mawai.ghmbplus.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知表 Service 实现类
 *
 * @author mawai
 * @since 2025-12-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification> implements NotificationService {

    @Override
    public List<Notification> getNotificationsByRecipientId(Long recipientId, int page, int size) {
        int offset = (page - 1) * size;
        return baseMapper.selectByRecipientIdWithPage(recipientId, offset, size);
    }

    @Override
    public int markAllAsRead(Long recipientId, LocalDateTime beforeTime) {
        return baseMapper.markAllAsRead(recipientId, beforeTime);
    }

    @Override
    public int deleteBeforeTime(LocalDateTime beforeTime) {
        return baseMapper.deleteBeforeTime(beforeTime);
    }

    @Override
    public int markOneAsRead(Long notificationId) {
        // 调用 Mapper XML 方法，返回更新的行数（0或1）
        return baseMapper.markOneAsRead(notificationId);
    }

    @Override
    public Long countUnreadByRecipientId(Long recipientId) {
        return baseMapper.countUnreadByRecipientId(recipientId);
    }
}

