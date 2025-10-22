package com.mawai.ghmbplus.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 子评论业务对象（BO）- JOIN查询结果
 * 
 * <p>用于封装子评论查询时JOIN user表的结果，包含被回复者信息</p>
 * 
 * @author mawai
 * @since 2025-10-16
 */
@Data
public class ChildCommentBO {
    
    /**
     * 评论ID
     */
    private Long id;
    
    /**
     * GIF ID
     */
    private Long gifId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 父评论ID
     */
    private Long parentId;
    
    /**
     * 被回复者ID（冗余字段）
     */
    private Long parentUserId;
    
    /**
     * 根评论ID
     */
    private Long rootCommentId;
    
    /**
     * 评论内容
     */
    private String content;
    
    /**
     * 点赞数
     */
    private Long likeCount;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 用户昵称（来自user表）
     */
    private String nickname;
    
    /**
     * 用户头像（来自user表）
     */
    private String avatar;
    
    /**
     * 被回复者昵称（来自父评论的user表）
     */
    private String parentNickname;
}


