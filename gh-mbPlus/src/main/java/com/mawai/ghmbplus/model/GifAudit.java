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
 * GIF审核表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@TableName("gif_audit")
@Accessors(chain = true)
@Schema(name = "GifAudit", description = "GIF审核表")
public class GifAudit implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 审核ID
     */
    @Schema(description = "审核ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * GIF文件URL
     */
    @TableField("file_url")
    @Schema(description = "GIF文件URL")
    private String fileUrl;

    /**
     * 标题
     */
    @TableField("title")
    @Schema(description = "标题")
    private String title;

    /**
     * 描述
     */
    @TableField("description")
    @Schema(description = "描述")
    private String description;

    /**
     * 标签
     */
    @TableField("tags")
    @Schema(description = "标签")
    private String tags;

    /**
     * 创建时间
     */
    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 审核时间
     */
    @TableField("audit_at")
    @Schema(description = "审核时间")
    private LocalDateTime auditAt;
} 