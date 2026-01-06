package com.mawai.ghweixin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "LinuxDo OAuth回调请求")
public class LinuxDoCallbackDTO {

    @Schema(description = "授权码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @Schema(description = "浏览器指纹")
    private String fingerprint;
}
