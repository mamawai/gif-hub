package com.mawai.ghmbplus.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 站内通知表
 *
 * @author mawai
 * @since 2025-12-15
 */
@Getter
@Setter
@ToString
@TableName("notification")
@Accessors(chain = true)
@Schema(name = "Notification", description = "站内通知表")
public class Notification implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 通知ID
     */
    @Schema(description = "通知ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 接收者ID
     */
    @TableField("recipient_id")
    @Schema(description = "接收者ID")
    private Long recipientId;

    /**
     * 触发者ID
     */
    @TableField("sender_id")
    @Schema(description = "触发者ID")
    private Long senderId;

    /**
     * 动作类型：1-点赞GIF, 2-点赞评论, 3-评论GIF, 4-回复评论
     */
    @TableField("action_type")
    @Schema(description = "动作类型：1-点赞GIF, 2-点赞评论, 3-评论GIF, 4-回复评论")
    private Short actionType;

    /**
     * 关联的目标ID（GIF ID 或 Comment ID）
     */
    @TableField("target_id")
    @Schema(description = "关联的目标ID（GIF ID 或 Comment ID）")
    private Long targetId;

    /**
     * 内容快照（评论前50字或GIF标题）
     */
    @TableField("content_snapshot")
    @Schema(description = "内容快照（评论前50字或GIF标题）")
    private String contentSnapshot;

    /**
     * 是否已读
     */
    @TableField("is_read")
    @Schema(description = "是否已读")
    private Boolean isRead;

    /**
     * 创建时间
     */
    @TableField("create_time")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}

