package com.mawai.ghgif.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知VO
 *
 * @author mawai
 * @since 2025-12-15
 */
@Data
@Schema(description = "通知VO")
public class NotificationVO {

    @Schema(description = "通知ID")
    private Long id;

    @Schema(description = "触发者ID")
    private Long senderId;

    @Schema(description = "触发者昵称")
    private String senderNickname;

    @Schema(description = "动作类型：1-点赞GIF, 2-点赞评论, 3-评论GIF, 4-回复评论")
    private Short actionType;

    @Schema(description = "关联的目标ID（GIF ID 或 Comment ID）")
    private Long targetId;

    @Schema(description = "内容快照（评论前50字或GIF标题）")
    private String contentSnapshot;

    @Schema(description = "是否已读")
    private Boolean isRead;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}

