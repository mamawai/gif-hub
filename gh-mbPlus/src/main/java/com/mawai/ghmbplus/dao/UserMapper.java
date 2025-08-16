package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.model.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;


import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 用户表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

}
