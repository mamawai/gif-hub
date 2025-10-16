package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.model.CommentPendingDelete;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 待删除评论队列表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-10-16
 */
@Mapper
public interface CommentPendingDeleteMapper extends BaseMapper<CommentPendingDelete> {

}

