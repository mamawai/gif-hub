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
 * 评论点赞记录表
 * </p>
 *
 * @author mawai
 * @since 2025-10-17
 */
@Getter
@Setter
@ToString
@TableName("comment_like")
@Accessors(chain = true)
@Schema(name = "CommentLike", description = "评论点赞记录表")
public class CommentLike implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 自增ID
     */
    @Schema(description = "自增ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * 评论ID
     */
    @TableField("comment_id")
    @Schema(description = "评论ID")
    private Long commentId;

    /**
     * 点赞时间
     */
    @TableField("created_at")
    @Schema(description = "点赞时间")
    private LocalDateTime createdAt;
}

