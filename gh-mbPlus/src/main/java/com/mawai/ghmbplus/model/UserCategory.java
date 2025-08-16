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
 * 用户喜欢分类表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("user_like_category")
@Schema(name = "UserCategory", description = "用户喜欢分类表")
public class UserCategory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 喜欢分类ID
     */
    @Schema(description = "喜欢分类ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * 分类名称
     */
    @TableField("category_name")
    @Schema(description = "分类名称")
    private String categoryName;

    /**
     * 创建时间
     */
    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
