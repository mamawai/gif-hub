package com.mawai.ghmbplus.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论点赞业务对象（BO）- JOIN查询结果
 * 
 * <p>用于封装用户点赞评论查询时JOIN comment和user表的结果</p>
 * 
 * @author mawai
 * @since 2025-10-20
 */
@Data
public class CommentLikeBO {
    private Long commentId;
    private Long gifId;
    private String content;
    private Long userId;
    private String nickname;
    private String avatar;
    private LocalDateTime likedAt;
    private LocalDateTime createdAt;
    private Long likeCount;
}

