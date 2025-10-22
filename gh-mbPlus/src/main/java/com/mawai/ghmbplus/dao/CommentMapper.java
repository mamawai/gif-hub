package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.dto.ChildCommentBO;
import com.mawai.ghmbplus.dto.RootCommentBO;
import com.mawai.ghmbplus.model.Comment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 评论表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 批量更新评论点赞数
     */
    int updateLikeCountBatchByMap(@Param("incrementMap") Map<Long, Long> incrementMap);

    /**
     * 使用游标查询GIF的根评论列表（按时间由远到近）
     * @param gifId GIF ID
     * @param cursor 游标（createdAt），null表示从头开始
     * @param limit 数量限制
     * @return 根评论BO列表
     */
    List<RootCommentBO> selectRootCommentsByCursor(
        @Param("gifId") Long gifId,
        @Param("cursor") LocalDateTime cursor,
        @Param("limit") Integer limit
    );

    /**
     * 批量查询根评论的子评论数量
     * @param rootCommentIds 根评论ID列表
     * @return Map<根评论ID, 子评论数量>
     */
    @MapKey("root_comment_id")
    Map<Long, Integer> countChildCommentsBatch(@Param("rootCommentIds") List<Long> rootCommentIds);

    /**
     * 使用游标查询子评论列表（按时间由远到近）
     * @param rootCommentId 根评论ID
     * @param cursor 游标（createdAt），null表示从头开始
     * @param limit 数量限制
     * @return 子评论BO列表
     */
    List<ChildCommentBO> selectChildCommentsByCursor(
        @Param("rootCommentId") Long rootCommentId,
        @Param("cursor") LocalDateTime cursor,
        @Param("limit") Integer limit
    );

    /**
     * 批量查询根评论BY IDs（带用户信息）
     * @param commentIds 评论ID列表
     * @return 根评论BO列表
     */
    List<RootCommentBO> selectRootCommentsByIds(@Param("commentIds") List<Long> commentIds);

    /**
     * 批量查询子评论BY IDs（带用户信息）
     * @param commentIds 评论ID列表
     * @return 子评论BO列表
     */
    List<ChildCommentBO> selectChildCommentsByIds(@Param("commentIds") List<Long> commentIds);
    
    /**
     * 统计某个根评论的子评论数量
     * @param rootCommentId 根评论ID
     * @return 子评论数量
     */
    Long countChildComments(@Param("rootCommentId") Long rootCommentId);

    /**
     * 查询热门根评论（点赞数>=minLikes，按点赞数倒序）
     * @param gifId GIF ID
     * @param minLikes 最小点赞数
     * @param limit 返回数量限制
     * @return 热门根评论列表
     */
    List<RootCommentBO> selectHotRootComments(
        @Param("gifId") Long gifId,
        @Param("minLikes") Integer minLikes,
        @Param("limit") Integer limit
    );
}
