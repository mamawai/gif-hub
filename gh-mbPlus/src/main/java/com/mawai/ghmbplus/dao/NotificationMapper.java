package com.mawai.ghmbplus.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.ghmbplus.model.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知表 Mapper 接口
 *
 * @author mawai
 * @since 2025-12-15
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 分页查询用户的通知列表（按时间倒序）
     *
     * @param recipientId 接收者ID
     * @param offset 偏移量
     * @param limit 每页数量
     * @return 通知列表
     */
    List<Notification> selectByRecipientIdWithPage(
            @Param("recipientId") Long recipientId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 标记指定时间之前的未读通知为已读
     *
     * @param recipientId 接收者ID
     * @param beforeTime 时间阈值
     * @return 更新的行数
     */
    int markAllAsRead(@Param("recipientId") Long recipientId,
                      @Param("beforeTime") LocalDateTime beforeTime);

    /**
     * 删除指定时间之前的通知
     *
     * @param beforeTime 时间阈值
     * @return 删除的行数
     */
    int deleteBeforeTime(@Param("beforeTime") LocalDateTime beforeTime);

    /**
     * 标记单个通知为已读
     *
     * @param notificationId 通知ID
     * @return 更新的行数（0或1）
     */
    int markOneAsRead(@Param("notificationId") Long notificationId);

    /**
     * 统计用户的未读通知数量
     *
     * @param recipientId 接收者ID
     * @return 未读数量
     */
    Long countUnreadByRecipientId(@Param("recipientId") Long recipientId);
}

