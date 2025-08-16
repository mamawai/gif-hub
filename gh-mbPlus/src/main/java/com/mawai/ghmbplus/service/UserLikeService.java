package com.mawai.ghmbplus.service;

import com.mawai.ghmbplus.model.UserLike;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 用户点赞表 服务类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
public interface UserLikeService extends IService<UserLike> {

    /**
     * 批量基于唯一索引的插入或更新操作
     * 使用 MySQL 的 ON DUPLICATE KEY UPDATE 语法
     * 当 (user_id, gif_id) 已存在时更新 user_like_category_id，否则插入新记录
     *
     * @param userLikes 用户点赞记录列表
     */
    void insertOrUpdateBatchByUniqueKey(List<UserLike> userLikes);
}
