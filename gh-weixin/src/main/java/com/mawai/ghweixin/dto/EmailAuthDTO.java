package com.mawai.ghweixin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 邮箱验证DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "邮箱验证请求")
public class EmailAuthDTO {
    
    @Schema(description = "邮箱地址", required = true)
    private String email;
    
    @Schema(description = "验证码（发送验证码时不需要，验证时需要）")
    private String verificationCode;
} 