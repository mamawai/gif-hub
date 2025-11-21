package com.mawai.ghgif.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Giphy 请求")
public class GiphyDTO {

    @Schema(description = "giphyId", requiredMode = Schema.RequiredMode.REQUIRED)
    private String giphyId;

    @Schema(description = "用户名称", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String username;

    @Schema(description = "来源", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String source;

    @Schema(description = "描述", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String description;

    @Schema(description = "标题", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String title;
}
