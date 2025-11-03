package com.mawai.ghgif.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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

    @Schema(description = "gif标题", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    @Schema(description = "gif描述", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String description;

    @Schema(description = "gif标签", requiredMode = Schema.RequiredMode.REQUIRED)
    private String tags;

    /**
     * 验证参数
     */
    public String validate() {
        if (title == null || title.trim().isEmpty()) {
            return "标题不能为空";
        }

        if (tags == null || tags.isEmpty()) {
            return "标签不能为空";
        }

        List<String> tagList = List.of(tags.split(","));

        if (tagList.size() > 3) {
            return "最多只能有3个标签";
        }

        for (String eachTag : tagList) {
            if (eachTag == null || eachTag.trim().isEmpty()) {
                return "标签不能为空";
            }
            if (eachTag.length() > 10) {
                return "标签'" + eachTag + "'长度不能超过10个字符";
            }
        }

        return null;
    }
}
