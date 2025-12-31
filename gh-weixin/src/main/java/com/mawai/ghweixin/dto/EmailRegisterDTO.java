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
    
    @Schema(description = "邮箱地址", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;
    
    @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
    
    @Schema(description = "验证码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String verificationCode;
    
    @Schema(description = "用户昵称")
    private String nickname;

    @Schema(description = "浏览器指纹--虽然不可信")
    private String fingerprint;
} 