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
     * GIF ID
     */
    @TableField("gif_id")
    @Schema(description = "GIF ID")
    private Long gifId;

    /**
     * GIF文件URL
     */
    @TableField("file_url")
    @Schema(description = "GIF文件URL")
    private String fileUrl;

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