package com.mawai.ghweixin.listener;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mawai.ghcommon.service.CacheService;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    private final CacheService cacheService;

    @Async("taskExecutor")
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
        
        // 删除用户评论 - 先查询所有评论ID
        List<Comment> userComments = commentService.lambdaQuery().select(Comment::getId).eq(Comment::getUserId, userId).list();
        int commentCount = userComments.size();
        if (commentCount > 0) {
            // 删除评论前收集所有评论ID
            List<Long> commentIds = userComments.stream().map(Comment::getId).toList();
            
            // 删除评论
            commentService.lambdaUpdate().eq(Comment::getUserId, userId).remove();
            log.info("删除用户评论: userId={}, count={}", userId, commentCount);
            
            // 更新Redis缓存中的评论detail
            for (Long commentId : commentIds) {
                String cacheKey = "comment:detail:" + commentId;
                Map<String, String> commentDetail = cacheService.hashGetAll(cacheKey);
                if (!commentDetail.isEmpty()) {
                    commentDetail.put("nickname", "用户已注销");
                    commentDetail.put("content", "用户已注销内容无法查看");
                    // 获取原来的过期时间
                    Long ttl = cacheService.getExpire(cacheKey);
                    if (ttl != null && ttl > 0) {
                        cacheService.hashSetAll(cacheKey, commentDetail, ttl, TimeUnit.SECONDS);
                    } else {
                        cacheService.hashSetAll(cacheKey, commentDetail, 1, TimeUnit.DAYS);
                    }
                    log.info("更新评论缓存: commentId={}", commentId);
                }
            }
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