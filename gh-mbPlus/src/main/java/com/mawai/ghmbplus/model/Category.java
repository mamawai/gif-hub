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
 * 分类表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@TableName("category")
@Accessors(chain = true)
@Schema(name = "Category", description = "分类表")
public class Category implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分类ID
     */
    @Schema(description = "分类ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 分类名称
     */
    @TableField("name")
    @Schema(description = "分类名称")
    private String name;

    /**
     * 分类图标
     */
    @TableField("icon")
    @Schema(description = "分类图标")
    private String icon;

    /**
     * 排序值
     */
    @TableField("sort_order")
    @Schema(description = "排序值")
    private Integer sortOrder;

    /**
     * 状态：0禁用，1启用
     */
    @TableField("status")
    @Schema(description = "状态：0禁用，1启用")
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
