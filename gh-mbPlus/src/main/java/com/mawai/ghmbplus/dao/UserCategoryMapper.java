package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.model.UserCategory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;


import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 用户喜欢分类表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Mapper
public interface UserCategoryMapper extends BaseMapper<UserCategory> {

    /**
     * 判断用户是否已经有一个同名的分类
     * @param userId 用户ID
     * @param categoryName 分类名称
     * @return 是否已经有一个同名的分类
     */
    boolean existsByUserIdAndCategoryName(Long userId, String categoryName);
}
