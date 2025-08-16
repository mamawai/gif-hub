package com.mawai.ghmbplus.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * <p>
 * 评论表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@TableName("comment")
@Accessors(chain = true)
@Schema(name = "Comment", description = "评论表")
public class Comment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 评论ID
     */
    @Schema(description = "评论ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * GIF ID
     */
    @TableField("gif_id")
    @Schema(description = "GIF ID")
    private Long gifId;

    /**
     * 用户ID
     */
    @TableField("user_id")
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * 父评论ID
     */
    @TableField("parent_id")
    @Schema(description = "父评论ID")
    private Long parentId;

    /**
     * 评论内容
     */
    @TableField("content")
    @Schema(description = "评论内容")
    private String content;

    /**
     * 状态：0删除，1正常
     */
    @TableField("status")
    @Schema(description = "状态：0删除，1正常")
    private Byte status;

    /**
     * 点赞数
     */
    @TableField("like_count")
    @Schema(description = "点赞数")
    private Integer likeCount;

    /**
     * 创建时间
     */
    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
