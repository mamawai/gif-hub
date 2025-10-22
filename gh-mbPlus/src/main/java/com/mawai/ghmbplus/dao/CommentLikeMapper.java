package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.dto.CommentLikeBO;
import com.mawai.ghmbplus.model.CommentLike;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

/**
 * <p>
 * 评论点赞记录表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-10-17
 */
@Mapper
public interface CommentLikeMapper extends BaseMapper<CommentLike> {

    /**
     * 批量查询用户对评论的点赞状态
     * @param userId 用户ID
     * @param commentIds 评论ID列表
     * @return 已点赞的评论ID集合
     */
    Set<Long> selectLikedCommentIds(@Param("userId") Long userId, 
                                     @Param("commentIds") List<Long> commentIds);

    /**
     * 分页查询用户点赞的评论（带评论详情）
     * @param userId 用户ID
     * @param offset 偏移量
     * @param limit 限制数量
     * @return 评论BO列表
     */
    List<CommentLikeBO> selectUserLikedCommentsBO(@Param("userId") Long userId,
                                                   @Param("offset") Integer offset,
                                                   @Param("limit") Integer limit);

    /**
     * 批量插入评论点赞记录（使用唯一键去重）
     * 使用 MySQL 的 INSERT IGNORE 语法，如果记录已存在则忽略
     * 利用 uk_user_comment(user_id, comment_id) 唯一索引
     * 
     * @param commentLikes 评论点赞记录列表
     * @return 影响的行数
     */
    int batchInsertIgnore(@Param("list") List<CommentLike> commentLikes);
    
    /**
     * 批量删除评论点赞记录
     * 
     * @param commentLikes 评论点赞记录列表（需要userId和commentId）
     * @return 影响的行数
     */
    int batchDelete(@Param("list") List<CommentLike> commentLikes);
}
