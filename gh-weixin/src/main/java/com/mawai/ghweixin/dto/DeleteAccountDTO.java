package com.mawai.ghweixin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 账号注销请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "账号注销请求")
public class DeleteAccountDTO {
    
    @Schema(description = "密码（RSA加密）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}