package com.mawai.ghmbplus.service.impl;

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
}
