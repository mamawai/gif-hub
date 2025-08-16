package com.mawai.ghgif.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Gif上传请求")
public class GifDTO {

    @Schema(description = "gif文件", requiredMode = Schema.RequiredMode.REQUIRED)
    private MultipartFile file;

    // 不传userId，使用satoken获取
    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long userId;

    @Schema(description = "gif标题", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String title;

    @Schema(description = "gif描述", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String description;
}
