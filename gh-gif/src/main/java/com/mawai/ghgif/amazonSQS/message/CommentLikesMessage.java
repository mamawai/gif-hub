package com.mawai.ghgif.amazonSQS.message;

import com.mawai.ghmbplus.model.CommentLike;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 评论点赞消息（支持新增和删除）
 * 
 * @author mawai
 * @since 2025-10-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentLikesMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 新增的点赞记录
     */
    private List<CommentLike> newLikes;
    
    /**
     * 删除的点赞记录（取消点赞）
     */
    private List<CommentLike> deleteLikes;
}

