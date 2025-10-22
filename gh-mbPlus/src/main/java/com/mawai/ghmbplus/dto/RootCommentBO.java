package com.mawai.ghmbplus.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 根评论业务对象（BO）- JOIN查询结果
 * 
 * <p>用于封装根评论查询时JOIN user表的结果</p>
 * 
 * @author mawai
 * @since 2025-10-16
 */
@Data
public class RootCommentBO {
    
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
}

