package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.model.Comment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;


import org.apache.ibatis.annotations.Mapper;

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

}
