package com.mawai.ghgif.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "批量GIF上传请求")
public class BatchGifUploadDTO {

    @Schema(description = "gif文件列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<MultipartFile> files;

    // 不传userId，使用satoken获取
    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long userId;

    @Schema(description = "gif标题列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> titles;

    @Schema(description = "gif描述列表", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<String> descriptions;

    @Schema(description = "gif标签列表（每个GIF的标签数组）", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> tags;

    /**
     * 验证批量上传参数
     * @return 验证结果消息，null表示验证通过
     */
    public String validate() {
        if (files == null || files.isEmpty()) {
            return "文件列表不能为空";
        }
        
        if (userId == null || userId <= 0) {
            return "用户ID不能为空";
        }

        if (titles == null || titles.size() != files.size()) {
            return "标题数量必须与文件数量一致";
        }

        if (tags == null || tags.size() != files.size()) {
            return "标签数量必须与文件数量一致";
        }

        // 验证每个文件的标题和标签
        for (int i = 0; i < files.size(); i++) {
            if (titles.get(i) == null || titles.get(i).trim().isEmpty()) {
                return "第" + (i + 1) + "个文件的标题不能为空";
            }

            String fileTags = tags.get(i);
            if (fileTags == null || fileTags.isBlank()) {
                return "第" + (i + 1) + "个文件必须有标签内容";
            }

            List<String> fileTagsList = List.of(fileTags.split(","));
            if (fileTagsList.size() > 3) {
                return "第" + (i + 1) + "个文件最多只能有3个标签";
            }

            // 验证每个标签长度
            for (String tag : fileTagsList) {
                if (tag == null || tag.trim().isEmpty()) {
                    return "第" + (i + 1) + "个文件的标签不能为空";
                }
                if (tag.length() > 10) {
                    return "第" + (i + 1) + "个文件的标签'" + tag + "'长度不能超过10个字符";
                }
            }
        }
        
        return null; // 验证通过
    }

    /**
     * 转换为GifDTO列表
     * @return GifDTO列表
     */
    public List<GifDTO> toGifDTOList() {
        List<GifDTO> gifDTOList = new ArrayList<>();
        
        for (int i = 0; i < files.size(); i++) {
            GifDTO gifDTO = new GifDTO();
            gifDTO.setFile(files.get(i));
            gifDTO.setUserId(userId);

            // 设置标题（必填）
            gifDTO.setTitle(titles.get(i).trim());
            
            // 设置描述（如果有）
            if (descriptions != null && i < descriptions.size() && descriptions.get(i) != null && !descriptions.get(i).trim().isEmpty()) {
                gifDTO.setDescription(descriptions.get(i).trim());
            }

            // 设置标签（必填）
            gifDTO.setTags(tags.get(i));

            gifDTOList.add(gifDTO);
        }
        
        return gifDTOList;
    }
}
