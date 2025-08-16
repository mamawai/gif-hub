package com.mawai.ghweixin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 邮箱注册DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "邮箱注册请求")
public class EmailRegisterDTO {
    
    @Schema(description = "邮箱地址", required = true)
    private String email;
    
    @Schema(description = "密码", required = true)
    private String password;
    
    @Schema(description = "验证码", required = true)
    private String verificationCode;
    
    @Schema(description = "用户昵称")
    private String nickname;
} 