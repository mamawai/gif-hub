package com.mawai.ghgif.amazonSQS.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 评论消息（用于SQS异步处理）
 * 
 * @author mawai
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * 预生成的评论ID（使用雪花算法）
     */
    private Long commentId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * GIF ID
     */
    private Long gifId;
    
    /**
     * 评论内容
     */
    private String content;
    
    /**
     * 父评论ID（可为空，为空表示根评论）
     */
    private String parentId;

}

