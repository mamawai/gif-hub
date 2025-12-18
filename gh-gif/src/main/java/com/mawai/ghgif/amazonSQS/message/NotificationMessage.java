package com.mawai.ghgif.amazonSQS.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 站内通知消息
 * 用于异步处理通知的发送
 *
 * @author mawai
 * @since 2025-12-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 接收者ID
     */
    private Long recipientId;

    /**
     * 触发者ID
     */
    private Long senderId;

    /**
     * 动作类型：1-点赞GIF, 2-点赞评论, 3-评论GIF, 4-回复评论
     */
    private Short actionType;

    /**
     * 关联的目标ID（GIF ID 或 Comment ID）
     */
    private Long targetId;

    /**
     * 内容快照（评论前50字或GIF标题）
     */
    private String contentSnapshot;
}

