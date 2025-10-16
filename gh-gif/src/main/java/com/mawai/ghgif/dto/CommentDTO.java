package com.mawai.ghgif.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 评论请求DTO
 */
@Data
public class CommentDTO {
    
    @NotNull(message = "GIF ID不能为空")
    private Long gifId;
    
    /**
     * 父评论ID（回复时必填，根评论时为null）
     */
    private Long parentId;
    
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 50, message = "评论内容不能超过50字符")
    private String content;
}

