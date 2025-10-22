package com.mawai.ghmbplus.service.impl;

import com.mawai.ghmbplus.model.UserLike;
import com.mawai.ghmbplus.dao.UserLikeMapper;
import com.mawai.ghmbplus.service.UserLikeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 用户点赞表 服务实现类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Slf4j
@Service
public class UserLikeServiceImpl extends ServiceImpl<UserLikeMapper, UserLike> implements UserLikeService {

    @Override
    public void insertOrUpdateBatchByUniqueKey(List<UserLike> userLikes) {
        if (userLikes == null || userLikes.isEmpty()) {
            return;
        }
        try {
            this.baseMapper.insertOrUpdateBatchByUniqueKey(userLikes);
        } catch (Exception e) {
            log.error("批量插入或更新用户点赞记录失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public boolean existsByUserIdAndGifId(Long userId, Long gifId) {
        try {
            return this.baseMapper.existsByUserIdAndGifId(userId, gifId);
        } catch (Exception e) {
            log.error("判断用户是否点赞了某个GIF失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public void batchDelete(List<UserLike> userLikes) {
        try {
            this.baseMapper.batchDelete(userLikes);
        } catch (Exception e) {
            log.error("批量删除用户点赞记录失败: {}", e.getMessage(), e);
        }   
    }
}
