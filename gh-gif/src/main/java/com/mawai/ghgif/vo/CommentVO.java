package com.mawai.ghgif.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论返回VO
 */
@Data
public class CommentVO {
    
    private String id;                  // 评论ID
    private Long gifId;                 // GIF ID
    private Long userId;                // 用户ID
    private String nickname;            // 用户昵称
    private String avatar;              // 用户头像
    private String parentId;            // 父评论ID
    private String rootCommentId;       // 根评论ID
    private Long parentUserId;        // 被回复者ID（仅子评论有）
    private String parentNickname;      // 被回复者昵称（仅子评论有）
    private String content;             // 评论内容
    private Long likeCount;             // 点赞数（合并Redis增量）
    private Integer childCount;         // 子评论数量（仅根评论有）
    private LocalDateTime createdAt;    // 创建时间
    private Boolean isLiked = false;    // 当前用户是否点赞
    private List<CommentVO> children;   // 子评论列表（懒加载时使用）
}


