package com.mawai.ghmbplus.service;

import com.mawai.ghmbplus.model.UserCategory;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 用户喜欢分类表 服务类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
public interface UserCategoryService extends IService<UserCategory> {

    /**
     * 判断用户是否已经有一个同名的分类
     * @param userId 用户ID
     * @param categoryName 分类名称
     * @return 是否已经有一个同名的分类
     */
    boolean existsByUserIdAndCategoryName(Long userId, String categoryName);

    /**
     * 获取用户最小的分类ID
     * @param userId 用户ID
     * @return 最小的分类ID，如果没有则返回null
     */
    Long getMinCategoryIdByUserId(Long userId);
}
