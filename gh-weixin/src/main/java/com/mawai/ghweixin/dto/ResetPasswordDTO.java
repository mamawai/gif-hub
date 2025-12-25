package com.mawai.ghweixin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 重置密码请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "重置密码请求")
public class ResetPasswordDTO {

    @Schema(description = "邮箱地址", requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    @Schema(description = "验证码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String verificationCode;

    @Schema(description = "新密码（RSA加密）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String newPassword;
}
