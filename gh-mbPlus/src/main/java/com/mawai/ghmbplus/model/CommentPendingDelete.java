package com.mawai.ghmbplus.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serial;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * <p>
 * 待删除评论队列表
 * </p>
 *
 * @author mawai
 * @since 2025-10-16
 */
@Getter
@Setter
@ToString
@TableName("comment_pending_delete")
@Accessors(chain = true)
@Schema(name = "CommentPendingDelete", description = "待删除评论队列表")
public class CommentPendingDelete implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 自增ID
     */
    @Schema(description = "自增ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 待删除的评论ID
     */
    @TableField("comment_id")
    @Schema(description = "待删除的评论ID")
    private Long commentId;

}

