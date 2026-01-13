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
import lombok.experimental.Accessors;

@Getter
@Setter
@TableName("linuxdo_invite_code")
@Accessors(chain = true)
@Schema(name = "LinuxdoInviteCode", description = "LinuxDo邀请码表")
public class LinuxdoInviteCode implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("code")
    @Schema(description = "邀请码")
    private String code;

    @TableField("email")
    @Schema(description = "领取邮箱")
    private String email;

    @TableField("claimed_at")
    @Schema(description = "领取时间")
    private LocalDateTime claimedAt;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
