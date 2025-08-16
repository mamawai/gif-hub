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
 * 用户反馈表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@TableName("feedback")
@Accessors(chain = true)
@Schema(name = "Feedback", description = "用户反馈表")
public class Feedback implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 反馈ID
     */
    @Schema(description = "反馈ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * 反馈内容
     */
    @TableField("content")
    @Schema(description = "反馈内容")
    private String content;

    /**
     * 联系方式
     */
    @TableField("contact")
    @Schema(description = "联系方式")
    private String contact;

    /**
     * 状态：0未处理，1已处理
     */
    @TableField("status")
    @Schema(description = "状态：0未处理，1已处理")
    private Byte status;

    /**
     * 回复内容
     */
    @TableField("reply")
    @Schema(description = "回复内容")
    private String reply;

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
