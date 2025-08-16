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
 * GIF资源表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@TableName("gif")
@Accessors(chain = true)
@Schema(name = "Gif", description = "GIF资源表")
public class Gif implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * GIF ID
     */
    @Schema(description = "GIF ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 上传用户ID
     */
    @TableField("user_id")
    @Schema(description = "上传用户ID")
    private Long userId;

    /**
     * GIF标题
     */
    @TableField("title")
    @Schema(description = "GIF标题")
    private String title;

    /**
     * GIF描述
     */
    @TableField("description")
    @Schema(description = "GIF描述")
    private String description;

    /**
     * GIF文件URL
     */
    @TableField("file_url")
    @Schema(description = "GIF文件URL")
    private String fileUrl;

    /**
     * 文件大小(KB)
     */
    @TableField("file_size")
    @Schema(description = "文件大小(KB)")
    private Integer fileSize;

    /**
     * 宽度(像素)
     */
    @TableField("width")
    @Schema(description = "宽度(像素)")
    private Integer width;

    /**
     * 高度(像素)
     */
    @TableField("height")
    @Schema(description = "高度(像素)")
    private Integer height;

    /**
     * 时长(毫秒)
     */
    @TableField("duration")
    @Schema(description = "时长(毫秒)")
    private Integer duration;

    /**
     * 状态：0下架，1正常
     */
    @TableField("status")
    @Schema(description = "状态：0下架，1正常")
    private Byte status;

    /**
     * 查看次数
     */
    @TableField("view_count")
    @Schema(description = "查看次数")
    private Integer viewCount;

    /**
     * 点赞次数
     */
    @TableField("like_count")
    @Schema(description = "点赞次数")
    private Integer likeCount;

    /**
     * 下载次数
     */
    @Schema(description = "下载次数")
    @TableField("download_count")
    private Integer downloadCount;

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
