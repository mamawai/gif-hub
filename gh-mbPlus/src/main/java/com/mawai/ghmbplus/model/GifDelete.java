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
 * GIF删除表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@TableName("gif_delete")
@Accessors(chain = true)
@Schema(name = "GifDelete", description = "GIF删除表")
public class GifDelete implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 删除ID
     */
    @Schema(description = "删除ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * GIF文件URL
     */
    @TableField("file_url")
    @Schema(description = "GIF文件URL")
    private String fileUrl;

    @TableField("file_Id")
    @Schema(description = "文件ID")
    private String fileId;

    /**
     * 创建时间
     */
    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
