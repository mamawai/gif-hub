package com.mawai.ghgif.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "点赞请求")
public class LikeRequestDTO {

    @Schema(description = "gif文件id", required = true)
    private String fileId;

    @Schema(description = "用户id", required = true)
    private Long userId;

    @Schema(description = "是否点赞", required = true)
    private Boolean isLike;
}
