package com.mawai.ghweixin.listener;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mawai.ghcommon.service.UserNicknameCacheService;
import com.mawai.ghmbplus.dao.CommentLikeMapper;
import com.mawai.ghmbplus.dao.UserMapper;
import com.mawai.ghmbplus.model.Comment;
import com.mawai.ghmbplus.model.CommentLike;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.UserCategory;
import com.mawai.ghmbplus.model.UserLike;
import com.mawai.ghmbplus.service.CommentService;
import com.mawai.ghmbplus.service.GifService;
import com.mawai.ghmbplus.service.UserCategoryService;
import com.mawai.ghmbplus.service.UserLikeService;
import com.mawai.ghweixin.event.AccountDeleteEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeleteEventListener {

    private final GifService gifService;
    private final CommentService commentService;
    private final UserLikeService userLikeService;
    private final CommentLikeMapper commentLikeMapper;
    private final UserCategoryService userCategoryService;
    private final UserMapper userMapper;
    private final UserNicknameCacheService userNicknameCacheService;

    @Async("wxVirtualExecutor")
    @EventListener
    @Transactional(rollbackFor = Exception.class)
    public void handleAccountDelete(AccountDeleteEvent event) {
        Long userId = event.getUserId();
        String email = event.getEmail();
        
        log.info("开始异步处理账号注销: userId={}, email={}", userId, email);
        
        // 删除用户上传的GIF
        int gifCount = gifService.lambdaQuery().eq(Gif::getUserId, userId).count().intValue();
        if (gifCount > 0) {
            gifService.lambdaUpdate().eq(Gif::getUserId, userId).remove();
            log.info("删除用户GIF: userId={}, count={}", userId, gifCount);
        }
        
        // 删除用户评论
        int commentCount = commentService.lambdaQuery().eq(Comment::getUserId, userId).count().intValue();
        if (commentCount > 0) {
            commentService.lambdaUpdate().eq(Comment::getUserId, userId).remove();
            log.info("删除用户评论: userId={}, count={}", userId, commentCount);
        }
        
        // 删除用户昵称缓存
        if (userNicknameCacheService.deleteNickname(userId)) {
            log.info("删除用户昵称缓存: userId={}", userId);
        } else {
            log.warn("删除用户昵称缓存失败: userId={}", userId);
        }
        
        // 删除用户GIF点赞
        int userLikeCount = userLikeService.lambdaQuery().eq(UserLike::getUserId, userId).count().intValue();
        if (userLikeCount > 0) {
            userLikeService.lambdaUpdate().eq(UserLike::getUserId, userId).remove();
            log.info("删除用户GIF点赞: userId={}, count={}", userId, userLikeCount);
        }
        
        // 删除用户评论点赞
        QueryWrapper<CommentLike> commentLikeWrapper = new QueryWrapper<>();
        commentLikeWrapper.eq("user_id", userId);
        int commentLikeCount = commentLikeMapper.selectCount(commentLikeWrapper).intValue();
        if (commentLikeCount > 0) {
            commentLikeMapper.delete(commentLikeWrapper);
            log.info("删除用户评论点赞: userId={}, count={}", userId, commentLikeCount);
        }
        
        // 删除用户喜欢分类
        int categoryCount = userCategoryService.lambdaQuery().eq(UserCategory::getUserId, userId).count().intValue();
        if (categoryCount > 0) {
            userCategoryService.lambdaUpdate().eq(UserCategory::getUserId, userId).remove();
            log.info("删除用户喜欢分类: userId={}, count={}", userId, categoryCount);
        }
        
        // 删除用户记录
        userMapper.deleteById(userId);
        log.info("删除用户记录: userId={}", userId);
        
        // 清除Sa-Token会话
        StpUtil.logout(userId);
        log.info("清除用户会话: userId={}", userId);
        
        log.info("账号注销异步处理完成: userId={}, email={}", userId, email);
    }
}