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
 * GIF删除失败表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@TableName("gif_delete_failed")
@Accessors(chain = true)
@Schema(name = "GifDeleteFailed", description = "GIF删除失败表")
public class GifDeleteFailed implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 失败ID
     */
    @Schema(description = "失败ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

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
} 