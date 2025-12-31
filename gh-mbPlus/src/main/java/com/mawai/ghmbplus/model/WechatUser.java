package com.mawai.ghmbplus.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 微信用户表
 * </p>
 *
 * @author mawai
 * @since 2025-12-27
 */
@Getter
@Setter
@ToString
@TableName("wechat_user")
@Accessors(chain = true)
@Schema(name = "WechatUser", description = "微信用户表")
public class WechatUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 微信用户ID
     */
    @Schema(description = "微信用户ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 微信openId
     */
    @TableField("open_id")
    @Schema(description = "微信openId")
    private String openId;

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

    /**
     * 最后一次登录的用户ID
     */
    @TableField("last_login_user_id")
    @Schema(description = "最后一次登录的用户ID")
    private Long lastLoginUserId;
}
