package com.mawai.ghgif.service;

import com.mawai.ghmbplus.model.UserCategory;

import java.util.List;

/**
 * 用户喜欢分类服务接口
 * 
 * @author mawai
 * @since 2025-08-26
 */
public interface UserCateLikeService {

    /**
     * 新增用户喜欢分类
     * @param categoryName 分类名称
     * @return 是否新增成功
     */
    boolean addCategory(String categoryName);

    /**
     * 删除用户喜欢分类
     * @param categoryId 分类ID
     * @return 是否删除成功
     */
    boolean deleteCategory(Long categoryId);

    /**
     * 修改用户喜欢分类
     * @param categoryId 分类ID
     * @param categoryName 分类名称
     * @return 是否修改成功
     */
    boolean updateCategory(Long categoryId, String categoryName);

    /**
     * 查询所有用户喜欢分类
     * @return 分类列表
     */
    List<UserCategory> list();
}
