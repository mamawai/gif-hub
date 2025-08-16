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
 * 热门搜索表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("hot_search")
@Schema(name = "HotSearch", description = "热门搜索表")
public class HotSearch implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ID
     */
    @Schema(description = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关键词
     */
    @TableField("keyword")
    @Schema(description = "关键词")
    private String keyword;

    /**
     * 搜索次数
     */
    @TableField("search_count")
    @Schema(description = "搜索次数")
    private Integer searchCount;

    /**
     * 状态：0下线，1正常
     */
    @TableField("status")
    @Schema(description = "状态：0下线，1正常")
    private Byte status;

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
