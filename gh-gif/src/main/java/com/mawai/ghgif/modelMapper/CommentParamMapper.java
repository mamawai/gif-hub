package com.mawai.ghgif.modelMapper;

import com.mawai.ghgif.vo.CommentVO;
import com.mawai.ghmbplus.dto.ChildCommentBO;
import com.mawai.ghmbplus.dto.CommentDetailCacheBO;
import com.mawai.ghmbplus.dto.CommentLikeBO;
import com.mawai.ghmbplus.dto.RootCommentBO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Comment实体和VO转换映射器
 */
@Mapper(componentModel = "spring")
public interface CommentParamMapper {

    /**
     * RootCommentBO转CommentVO（Long ID 转 String）
     * 注意：isLiked 字段会在 Service 层通过查询用户点赞状态来设置
     */
    @Mapping(target = "id", expression = "java(rootCommentBO.getId() != null ? rootCommentBO.getId().toString() : null)")
    @Mapping(target = "parentId", constant = "")
    @Mapping(target = "rootCommentId", constant = "")
    @Mapping(target = "parentNickname", ignore = true)
    @Mapping(target = "childCount", ignore = true)
    @Mapping(target = "children", ignore = true)
    @Mapping(target = "isLiked", ignore = true)  // 在Service层设置
    CommentVO boToCommentVO(RootCommentBO rootCommentBO);

    /**
     * ChildCommentBO转CommentVO（Long ID 转 String）
     * 注意：isLiked 字段会在 Service 层通过查询用户点赞状态来设置
     */
    @Mapping(target = "id", expression = "java(childCommentBO.getId() != null ? childCommentBO.getId().toString() : null)")
    @Mapping(target = "parentId", expression = "java(childCommentBO.getParentId() != null ? childCommentBO.getParentId().toString() : null)")
    @Mapping(target = "rootCommentId", expression = "java(childCommentBO.getRootCommentId() != null ? childCommentBO.getRootCommentId().toString() : null)")
    @Mapping(target = "childCount", ignore = true)
    @Mapping(target = "children", ignore = true)
    @Mapping(target = "isLiked", ignore = true)  // 在Service层设置
    CommentVO childBoToCommentVO(ChildCommentBO childCommentBO);
    
    /**
     * CommentLikeBO转CommentVO（Long ID 转 String）
     * 用于用户点赞历史查询，isLiked 固定为 true（因为是点赞历史）
     */
    @Mapping(target = "id", expression = "java(commentLikeBO.getCommentId() != null ? commentLikeBO.getCommentId().toString() : null)")
    @Mapping(target = "parentId", constant = "")
    @Mapping(target = "rootCommentId", constant = "")
    @Mapping(target = "parentNickname", ignore = true)
    @Mapping(target = "childCount", ignore = true)
    @Mapping(target = "children", ignore = true)
    @Mapping(target = "isLiked", expression = "java(Boolean.TRUE)")  // 点赞历史，必然是已点赞
    CommentVO likeBoToCommentVO(CommentLikeBO commentLikeBO);
    
    /**
     * CommentLikeBO列表转CommentVO列表
     */
    List<CommentVO> likeBoListToCommentVOList(List<CommentLikeBO> commentLikeBOs);
    
    /**
     * CommentDetailCacheBO转CommentVO（从 Redis Hash 缓存读取后的转换）
     * 
     * <p>统一处理根评论和子评论，根据 parentId 和 rootCommentId 是否为 null 自动区分</p>
     * 
     * @param cacheBO 缓存BO（从 Redis Hash 解析得到）
     * @return CommentVO
     */
    @Mapping(target = "id", expression = "java(cacheBO.getId() != null ? cacheBO.getId().toString() : null)")
    @Mapping(target = "parentId", expression = "java(cacheBO.getParentId() != null ? cacheBO.getParentId().toString() : \"\")")
    @Mapping(target = "rootCommentId", expression = "java(cacheBO.getRootCommentId() != null ? cacheBO.getRootCommentId().toString() : \"\")")
    @Mapping(target = "childCount", ignore = true)  // 在Service层设置
    @Mapping(target = "children", ignore = true)    // 在Service层设置
    @Mapping(target = "isLiked", ignore = true)     // 在Service层设置
    CommentVO cacheBoToCommentVO(CommentDetailCacheBO cacheBO);
}

