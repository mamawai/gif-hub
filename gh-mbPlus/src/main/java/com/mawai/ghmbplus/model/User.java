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
 * 用户表
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Getter
@Setter
@ToString
@TableName("user")
@Accessors(chain = true)
@Schema(name = "User", description = "用户表")
public class User implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    @Schema(description = "用户ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 微信open_id
     */
    @TableField("open_id")
    @Schema(description = "微信open_id")
    private String openId;

    /**
     * 邮箱地址
     */
    @TableField("email")
    @Schema(description = "邮箱地址")
    private String email;

    /**
     * 邮箱验证状态
     */
    @TableField("email_verified")
    @Schema(description = "邮箱验证状态")
    private Byte emailVerified;

    /**
     * 密码（加密存储）
     */
    @TableField("password")
    @Schema(description = "密码（加密存储）")
    private String password;
    
    /**
     * 用户昵称
     */
    @TableField("nickname")
    @Schema(description = "用户昵称")
    private String nickname;

    /**
     * 状态：0禁用，1正常
     */
    @TableField("status")
    @Schema(description = "状态：0禁用，1正常")
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
