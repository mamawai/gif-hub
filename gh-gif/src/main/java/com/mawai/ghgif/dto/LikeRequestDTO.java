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

    @Schema(description = "gif文件id", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileId;

    /**
     * 分类id
     * 如果为空，则表示取消点赞
     */
    @Schema(description = "分类id", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long userLikeCategoryId;

    @Schema(description = "是否点赞", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean isLike;
}
