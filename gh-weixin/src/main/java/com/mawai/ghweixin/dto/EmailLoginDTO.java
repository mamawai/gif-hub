package com.mawai.ghweixin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 邮箱登录DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "邮箱登录请求")
public class EmailLoginDTO {
    
    @Schema(description = "邮箱地址", required = true)
    private String email;
    
    @Schema(description = "密码（密码登录时需要）")
    private String password;
    
    @Schema(description = "验证码（验证码登录时需要）")
    private String verificationCode;
    
    @Schema(description = "登录类型：1-密码登录，2-验证码登录", required = true)
    private Integer loginType;
} 