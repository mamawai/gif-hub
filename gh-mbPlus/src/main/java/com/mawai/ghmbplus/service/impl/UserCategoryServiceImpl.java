package com.mawai.ghmbplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.ghmbplus.model.UserCategory;
import com.mawai.ghmbplus.dao.UserCategoryMapper;
import com.mawai.ghmbplus.service.UserCategoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * 用户喜欢分类表 服务实现类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Slf4j
@Service
public class UserCategoryServiceImpl extends ServiceImpl<UserCategoryMapper, UserCategory> implements UserCategoryService {

    @Override
    public boolean existsByUserIdAndCategoryName(Long userId, String categoryName) {
        try {
            return this.baseMapper.existsByUserIdAndCategoryName(userId, categoryName);
        } catch (Exception e) {
            log.error("判断用户是否已经有一个同名的分类失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public Long getMinCategoryIdByUserId(Long userId) {
        try {
            LambdaQueryWrapper<UserCategory> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserCategory::getUserId, userId)
                   .orderByAsc(UserCategory::getId)
                   .last("LIMIT 1");
            UserCategory category = this.getOne(wrapper);
            return category != null ? category.getId() : null;
        } catch (Exception e) {
            log.error("获取用户最小分类ID失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
