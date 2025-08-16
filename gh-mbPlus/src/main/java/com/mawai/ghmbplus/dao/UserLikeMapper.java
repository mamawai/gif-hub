package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.model.UserLike;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;


import org.apache.ibatis.annotations.Mapper;

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

}
