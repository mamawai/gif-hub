package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.model.UserLike;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 用户点赞表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Mapper
public interface UserLikeMapper extends BaseMapper<UserLike> {

    /**
     * 批量基于唯一键的插入或更新操作
     * 使用 MySQL 的 ON DUPLICATE KEY UPDATE 语法
     * 当 (user_id, gif_id) 已存在时更新 user_like_category_id，否则插入新记录
     * 
     * @param userLikes 用户点赞记录列表
     * @return 影响的行数
     */
    int insertOrUpdateBatchByUniqueKey(@Param("list") List<UserLike> userLikes);
}
