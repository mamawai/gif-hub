package com.mawai.ghgif.service;

import com.mawai.ghgif.dto.CommentDTO;
import com.mawai.ghgif.vo.CommentVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论处理服务接口
 * 
 * @author mawai
 */
public interface CommentProcessService {
    
    /**
     * 发表评论（支持根评论和回复）
     * 
     * @param commentDTO 评论DTO
     * @param userId 当前用户ID
     * @return 包含预生成评论ID的CommentVO
     */
    CommentVO addComment(CommentDTO commentDTO, Long userId);
    
    /**
     * 获取GIF根评论列表（游标+页码分页，带ZSet缓存）
     * 
     * @param gifId GIF ID
     * @param cursor 游标（createdAt），用于DB查询的起始点
     * @param page 页码（从1开始），用于计算ZSet索引范围
     * @param limit 每次获取数量
     * @param userId 当前用户ID
     * @return 根评论列表（包含热评）
     */
    List<CommentVO> getRootCommentsByCursor(String gifId, LocalDateTime cursor, Integer page, Integer limit, Long userId);
    
    /**
     * 获取子评论列表（游标+页码分页，带ZSet缓存）
     * 
     * @param rootCommentId 根评论ID
     * @param cursor 游标（createdAt），用于DB查询的起始点
     * @param page 页码（从1开始），用于计算ZSet索引范围
     * @param limit 每次获取数量
     * @param userId 当前用户ID
     * @return 子评论列表
     */
    List<CommentVO> getChildCommentsByCursor(String rootCommentId, LocalDateTime cursor, Integer page, Integer limit, Long userId);
    
    /**
     * 获取热门根评论（点赞>10，最多3条，带独立缓存）
     * 
     * @param gifId GIF ID
     * @param userId 当前用户ID
     * @return 热门根评论列表
     */
    List<CommentVO> getHotRootComments(String gifId, Long userId);
    
    /**
     * 分页查询用户点赞的评论历史
     * 
     * @param userId 用户ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 用户点赞的评论历史列表
     */
    List<CommentVO> getUserLikedComments(Long userId, Integer pageNum, Integer pageSize);
    
    /**
     * 点赞/取消点赞评论（双向操作）
     * 
     * @param commentId 评论ID
     * @param userId 用户ID
     * @param isLike true-点赞，false-取消点赞
     * @return 是否操作成功
     */
    boolean toggleCommentLike(String commentId, Long userId, Boolean isLike);
    
    /**
     * 删除评论（软删除）
     * 
     * @param commentId 评论ID
     * @param userId 用户ID
     * @return 是否删除成功
     */
    boolean deleteComment(String commentId, Long userId);
    
}


