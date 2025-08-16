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

    @Schema(description = "gif标题列表", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<String> titles;

    @Schema(description = "gif描述列表", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<String> descriptions;

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
            
            // 设置标题（如果有）
            if (titles != null && i < titles.size() && titles.get(i) != null && !titles.get(i).trim().isEmpty()) {
                gifDTO.setTitle(titles.get(i).trim());
            }
            
            // 设置描述（如果有）
            if (descriptions != null && i < descriptions.size() && descriptions.get(i) != null && !descriptions.get(i).trim().isEmpty()) {
                gifDTO.setDescription(descriptions.get(i).trim());
            }
            
            gifDTOList.add(gifDTO);
        }
        
        return gifDTOList;
    }
}
