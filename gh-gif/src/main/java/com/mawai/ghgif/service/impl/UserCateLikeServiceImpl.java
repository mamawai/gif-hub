package com.mawai.ghgif.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.ghgif.service.UserCateLikeService;
import com.mawai.ghmbplus.model.UserCategory;
import com.mawai.ghmbplus.service.UserCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户喜欢分类服务实现类
 * 
 * @author mawai
 * @since 2025-08-26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCateLikeServiceImpl implements UserCateLikeService {

    private final UserCategoryService userCategoryService;

    @Override
    public boolean addCategory(String categoryName) {
        try {
            // 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            
            // 检查该用户是否已经有同名的分类
            LambdaQueryWrapper<UserCategory> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(UserCategory::getUserId, userId)
                        .eq(UserCategory::getCategoryName, categoryName);
            
            if (userCategoryService.count(queryWrapper) > 0) {
                log.warn("用户 {} 尝试创建重复的分类: {}", userId, categoryName);
                return false;
            }
            
            // 创建新的分类
            UserCategory userCategory = new UserCategory();
            userCategory.setUserId(userId);
            userCategory.setCategoryName(categoryName);

            return userCategoryService.save(userCategory);
        } catch (Exception e) {
            log.error("新增用户分类失败", e);
            return false;
        }
    }

    @Override
    public boolean deleteCategory(Long categoryId) {
        try {
            // 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            
            // 检查该分类是否属于当前用户
            return userCategoryService.remove(
                new LambdaQueryWrapper<UserCategory>()
                        .eq(UserCategory::getId, categoryId)
                        .eq(UserCategory::getUserId, userId)
            );
        } catch (Exception e) {
            log.error("删除用户分类失败", e);
            return false;
        }
    }

    @Override
    public boolean updateCategory(Long categoryId, String categoryName) {
        try {
            // 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            
            // 检查该分类是否属于当前用户
            LambdaQueryWrapper<UserCategory> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(UserCategory::getId, categoryId)
                        .eq(UserCategory::getUserId, userId);
            
            UserCategory userCategory = userCategoryService.getOne(queryWrapper);
            if (userCategory == null) {
                log.warn("用户 {} 尝试修改不属于自己的分类: {}", userId, categoryId);
                return false;
            }
            
            // 检查新的分类名是否与该用户的其他分类重复
            LambdaQueryWrapper<UserCategory> duplicateCheckWrapper = new LambdaQueryWrapper<>();
            duplicateCheckWrapper.eq(UserCategory::getUserId, userId)
                                 .eq(UserCategory::getCategoryName, categoryName)
                                 .ne(UserCategory::getId, categoryId);
            
            if (userCategoryService.count(duplicateCheckWrapper) > 0) {
                log.warn("用户 {} 尝试将分类 {} 重命名为已存在的名称: {}", userId, categoryId, categoryName);
                return false;
            }
            
            // 更新分类名称
            LambdaUpdateWrapper<UserCategory> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(UserCategory::getId, categoryId)
                         .eq(UserCategory::getUserId, userId)
                         .set(UserCategory::getCategoryName, categoryName);
            
            return userCategoryService.update(updateWrapper);
        } catch (Exception e) {
            log.error("修改用户分类失败", e);
            return false;
        }
    }

    @Override
    public List<UserCategory> list() {
        try {
            // 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            
            // 查询该用户的所有分类
            LambdaQueryWrapper<UserCategory> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(UserCategory::getUserId, userId)
                        .orderByDesc(UserCategory::getCreatedAt);
            
            return userCategoryService.list(queryWrapper);
        } catch (Exception e) {
            log.error("查询用户分类列表失败", e);
            return List.of();
        }
    }
}
