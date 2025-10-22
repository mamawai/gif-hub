package com.mawai.ghgif.service.impl;

import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghgif.amazonSQS.message.CommentMessage;
import com.mawai.ghgif.annotation.RateLimiter;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.constant.RateLimiterType;
import com.mawai.ghgif.dto.CommentDTO;
import com.mawai.ghgif.modelMapper.CommentParamMapper;
import com.mawai.ghgif.service.CommentProcessService;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghgif.vo.CommentVO;
import com.mawai.ghmbplus.dao.CommentLikeMapper;
import com.mawai.ghmbplus.dao.CommentMapper;
import com.mawai.ghmbplus.dao.CommentPendingDeleteMapper;
import com.mawai.ghmbplus.dto.ChildCommentBO;
import com.mawai.ghmbplus.dto.CommentLikeBO;
import com.mawai.ghmbplus.dto.RootCommentBO;
import com.mawai.ghmbplus.model.Comment;
import com.mawai.ghmbplus.model.CommentPendingDelete;
import com.mawai.ghmbplus.service.CommentService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 评论处理服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentProcessServiceImpl implements CommentProcessService {

    private final CommentService commentService;
    private final CommentMapper commentMapper;
    private final CommentPendingDeleteMapper commentPendingDeleteMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final CommentParamMapper commentParamMapper;
    private final CacheService cacheService;
    private final MessageService messageService;
    
    private static final String COMMENT_LIKE_COUNT_KEY = "comment:like:"; // 评论点赞数缓存key
    private static final String USER_COMMENT_LIKE_KEY = "user:comment:like:"; // 用户评论点赞缓存key
    private static final String USER_COMMENT_DISLIKE_KEY = "user:comment:dislike:"; // 用户评论取消点赞缓存key
    private static final String COMMENT_DETAIL_ROOT_KEY = "comment:detail:root:"; // 评论详情根评论缓存key
    private static final String COMMENT_DETAIL_CHILD_KEY = "comment:detail:child:"; // 评论详情子评论缓存key
    private static final String COMMENT_ROOT_KEY = "comment:root:"; // 评论根评论缓存key
    private static final String COMMENT_CHILD_KEY = "comment:child:"; // 评论子评论缓存key
    private static final String COMMENT_HOT_ROOT_KEY = "comment:hot:root:"; // 热门根评论缓存key
    private static final String LOCK_COMMENT_ROOT_KEY = "lock:comment:root:"; // 评论根评论锁
    private static final String LOCK_COMMENT_CHILD_KEY = "lock:comment:child:"; // 评论子评论锁
    private static final String LOCK_COMMENT_HOT_ROOT_KEY = "lock:comment:hot:root:"; // 热门根评论锁
    private static final int EXPIRE_TIME = 2; // Redis过期时间（分钟）
    private static final int ZSET_CACHE_TTL = 30; // ZSet缓存过期时间（分钟）
    private static final int COMMENT_DETAIL_CACHE_TTL = 60; // 评论详情缓存过期时间（分钟）
    private static final int HOT_COMMENT_CACHE_TTL = 60; // 热门评论缓存过期时间（分钟，较长）
    private static final int LOCK_WAIT_TIME = 5; // 分布式锁等待时间（秒）
    private static final int CACHE_RETRY_TIMES = 3; // 等待缓存重试次数
    private static final int CACHE_RETRY_INTERVAL = 50; // 每次重试间隔（毫秒）

    @Value("${aws.sqs.base-queue-url}")
    private String SQS_QUEUE_URL;

    /**
     * 发表评论（支持根评论和回复）- 异步SQS处理
     * 限流每分钟只允许评论3次
     * 
     * @param commentDTO 评论DTO
     * @param userId 用户ID
     * @return 是否成功发送SQS消息
     */
    @Override
    @RateLimiter(permitsPerSecond = (3 / 60.0), bucketCapacity = 3, message = "评论过于频繁", type = RateLimiterType.COMMENT)
    public Boolean addComment(CommentDTO commentDTO, Long userId) {
        // 参数校验
        if (commentDTO == null) {
            throw new IllegalArgumentException("参数不能为空");
        }
        if (StrUtil.isBlank(commentDTO.getContent())) {
            throw new IllegalArgumentException("评论内容不能为空");
        }

        // 构建CommentMessage
        CommentMessage commentMessage = new CommentMessage();
        commentMessage.setUserId(userId);
        commentMessage.setGifId(commentDTO.getGifId());
        commentMessage.setContent(commentDTO.getContent().trim());
        commentMessage.setParentId(commentDTO.getParentId());

        // 发送到SQS
        try {
            messageService.send(
                cn.hutool.json.JSONUtil.toJsonStr(commentMessage), 
                SQS_QUEUE_URL,
                MessageType.COMMENT_MESSAGE
            );
            log.info("评论消息发送成功: userId={}, gifId={}", userId, commentDTO.getGifId());
            return true;
        } catch (Exception e) {
            log.error("评论消息发送失败: userId={}, gifId={}, error={}", 
                    userId, commentDTO.getGifId(), e.getMessage(), e);
            throw new RuntimeException("评论提交失败，请稍后重试", e);
        }
    }

    /**
     * 丰富根评论数据（子评论数量、点赞状态）
     * 
     * @param comments 根评论列表
     * @param userId 用户ID
     */
    private void enrichRootComments(List<CommentVO> comments, Long userId) {
        if (comments.isEmpty()) {
            return;
        }
        
        // 1. 批量查询子评论数量
        List<Long> rootCommentIds = comments.stream()
                .map(vo -> Long.parseLong(vo.getId()))
                .toList();
        Map<Long, Integer> childCountMap = commentMapper.countChildCommentsBatch(rootCommentIds);
        
        for (CommentVO vo : comments) {
            vo.setChildCount(childCountMap.get(Long.parseLong(vo.getId())));
            vo.setChildren(null); // 懒加载
        }
        
        // 2. 实时查询点赞状态
        if (userId != null) {
            batchCheckCommentLikeStatus(comments, userId);
        }
        
        // 3. 合并Redis增量点赞数
        mergeCommentLikeCounts(comments);
    }

    /**
     * 丰富子评论数据（点赞状态、点赞数）
     * 
     * @param comments 子评论列表
     * @param userId 用户ID
     */
    private void enrichChildComments(List<CommentVO> comments, Long userId) {
        if (comments.isEmpty()) {
            return;
        }
        
        // 子评论不再有子评论
        for (CommentVO vo : comments) {
            vo.setChildCount(0);
            vo.setChildren(null);
        }
        
        // 实时查询点赞状态
        if (userId != null) {
            batchCheckCommentLikeStatus(comments, userId);
        }

        // 合并Redis增量点赞数
        mergeCommentLikeCounts(comments);
    }

    /**
     * 点赞/取消点赞评论（复用GIF点赞逻辑）
     * 
     * @param commentId 评论ID
     * @param userId 用户ID
     * @param isLike true-点赞，false-取消点赞
     * @return 是否操作成功
     */
    @Override
    public boolean toggleCommentLike(String commentId, Long userId, Boolean isLike) {
        if (StrUtil.isBlank(commentId) || userId == null || isLike == null) {
            throw new IllegalArgumentException("参数不能为空");
        }

        try {
            // 创建Redis Keys
            String countKey = COMMENT_LIKE_COUNT_KEY + commentId;
            String likeSetKey = USER_COMMENT_LIKE_KEY + userId;
            String dislikeSetKey = USER_COMMENT_DISLIKE_KEY + userId;

            if (isLike) {
                // 点赞操作：检查dislike set，有则删除；无论如何都在like set中设置
                cacheService.commentLikeOperation(countKey, likeSetKey, dislikeSetKey, commentId, EXPIRE_TIME, TimeUnit.MINUTES);
                log.info("用户{}对评论{}点赞成功", userId, commentId);
            } else {
                // 取消点赞操作：检查like set，有则删除；没有则在dislike set中标记
                cacheService.commentDislikeOperation(countKey, likeSetKey, dislikeSetKey, commentId, EXPIRE_TIME, TimeUnit.MINUTES);
                log.info("用户{}取消评论{}点赞", userId, commentId);
            }
            return true;
        } catch (Exception e) {
            log.error("切换评论点赞状态失败: commentId={}, userId={}, isLike={}, 错误: {}", 
                     commentId, userId, isLike, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 删除评论（软删除，只删除根评论或子评论本身，不级联）
     * 
     * <p>删除策略</p>
     * <ul>
     *   <li>软删除：设置 status=0</li>
     *   <li>不级联删除：删除根评论时，子评论保留显示</li>
     *   <li>记录到表：将评论ID记录到 comment_pending_delete 表</li>
     *   <li>定时清理：定时任务从表中获取ID批量物理删除</li>
     * </ul>
     * 
     * @param commentId 评论ID
     * @param userId 用户ID
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteComment(String commentId, Long userId) {
        if (StrUtil.isBlank(commentId)|| userId == null) {
            throw new IllegalArgumentException("参数不能为空");
        }

        Comment comment = commentService.getById(Long.parseLong(commentId));
        if (comment == null) {
            throw new RuntimeException("评论不存在");
        }

        // 权限校验：只能删除自己的评论
        if (!comment.getUserId().equals(userId)) {
            throw new RuntimeException("无权删除他人评论");
        }

        // 软删除：更新status为0
        comment.setStatus((byte) 0);
        boolean result = commentService.updateById(comment);
        
        if (result) {
            // 将评论ID记录到待删除表
            CommentPendingDelete pendingDelete = new CommentPendingDelete().setCommentId(Long.parseLong(commentId));
            commentPendingDeleteMapper.insert(pendingDelete);
            
            // 删除相关缓存
            String zsetKey;
                if (comment.getRootCommentId() == null) {
                // 根评论：删除ZSet、详情缓存、热门评论列表缓存
                zsetKey = COMMENT_ROOT_KEY + comment.getGifId();
                cacheService.delete(COMMENT_DETAIL_ROOT_KEY + commentId);
                cacheService.delete(COMMENT_HOT_ROOT_KEY + comment.getGifId()); // 删除热门评论列表缓存
                } else {
                // 子评论：删除ZSet、详情缓存
                zsetKey = COMMENT_CHILD_KEY + comment.getRootCommentId();
                cacheService.delete(COMMENT_DETAIL_CHILD_KEY + commentId);
                }
            cacheService.zsetRemove(zsetKey, commentId);
            
            log.info("用户{}软删除评论{}成功，已记录到待删除表", userId, commentId);
        }
        
        return result;
    }

    /**
     * 分页查询用户点赞的评论历史
     * 
     * @param userId 用户ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 用户点赞的评论历史列表
     */
    @Override
    public List<CommentVO> getUserLikedComments(Long userId, Integer pageNum, Integer pageSize) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        
        int offset = (pageNum - 1) * pageSize;
        
        // 查询用户点赞的评论（返回BO）
        List<CommentLikeBO> likeBOs = commentLikeMapper.selectUserLikedCommentsBO(userId, offset, pageSize);
        
        if (likeBOs.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 使用 MapStruct 转换为CommentVO（BO → VO）
        List<CommentVO> result = commentParamMapper.likeBoListToCommentVOList(likeBOs);
        
        // 合并Redis增量点赞数
        mergeCommentLikeCounts(result);
        
        return result;
    }

    /**
     * 批量检查评论点赞状态（优先Redis，支持取消点赞和oldKey查询）
     * 
     * <p>查询顺序：</p>
     * <ol>
     *   <li>查询 like/dislike set（实时数据）</li>
     *   <li>查询 like/dislike :old set（缓存清理期间的数据连续性保证）</li>
     *   <li>批量查询数据库（兜底）</li>
     * </ol>
     */
    private void batchCheckCommentLikeStatus(List<CommentVO> comments, Long userId) {
        String likeSetKey = USER_COMMENT_LIKE_KEY + userId;
        String dislikeSetKey = USER_COMMENT_DISLIKE_KEY + userId;
        
        // 使用 getSafely 方法获取数据（避免竞态条件，key不存在会返回空Set）
        Set<String> likeSet = cacheService.getStringSetSafely(likeSetKey, false);
        Set<String> dislikeSet = cacheService.getStringSetSafely(dislikeSetKey, false);
        
        // 如果两个Set都为空，尝试查询oldKey
        if (likeSet.isEmpty() && dislikeSet.isEmpty()) {
            // 查询oldKey（缓存清理期间可能在这里）
            Set<String> oldLikeSet = cacheService.getStringSetSafely(likeSetKey + ":old", false);
            Set<String> oldDislikeSet = cacheService.getStringSetSafely(dislikeSetKey + ":old", false);
            
            // 如果oldKey也为空，直接批量查数据库
            if (oldLikeSet.isEmpty() && oldDislikeSet.isEmpty()) {
                List<Long> commentIds = comments.stream()
                        .map(vo -> Long.parseLong(vo.getId()))  // String 转 Long
                        .toList();
                Set<Long> likedIds = commentLikeMapper.selectLikedCommentIds(userId, commentIds);
                for (CommentVO vo : comments) {
                    vo.setIsLiked(likedIds.contains(Long.parseLong(vo.getId())));
                }
                return;
            }
            
            // 使用oldKey的数据
            List<Long> needDbCheckIds = new ArrayList<>();
            Map<Long, CommentVO> needDbCheckMap = new HashMap<>();
            
            for (CommentVO vo : comments) {
                String commentIdStr = vo.getId();  // 已经是 String 了
                if (!oldLikeSet.isEmpty() && oldLikeSet.contains(commentIdStr)) {
                    vo.setIsLiked(true);
                } else if (!oldDislikeSet.isEmpty() && oldDislikeSet.contains(commentIdStr)) {
                    vo.setIsLiked(false);
                } else {
                    // oldKey中也没有，需要查数据库
                    needDbCheckIds.add(Long.parseLong(vo.getId()));
                    needDbCheckMap.put(Long.parseLong(vo.getId()), vo);
                }
            }
            
            // 批量查数据库
            if (!needDbCheckIds.isEmpty()) {
                Set<Long> likedIds = commentLikeMapper.selectLikedCommentIds(userId, needDbCheckIds);
                for (Long commentId : needDbCheckIds) {
                    needDbCheckMap.get(commentId).setIsLiked(likedIds.contains(commentId));
                }
            }
            
            return;
        }
        
        // 使用当前的like/dislike set
        List<Long> needDbCheckIds = new ArrayList<>();
        Map<Long, CommentVO> needDbCheckMap = new HashMap<>();
        
        for (CommentVO vo : comments) {
            String commentIdStr = vo.getId();  // 已经是 String
            if (!likeSet.isEmpty() && likeSet.contains(commentIdStr)) {
                // 在like set中，已点赞
                vo.setIsLiked(true);
            } else if (!dislikeSet.isEmpty() && dislikeSet.contains(commentIdStr)) {
                // 在dislike set中，已取消点赞
                vo.setIsLiked(false);
            } else {
                // 都不在，需要查数据库
                needDbCheckIds.add(Long.parseLong(vo.getId()));  // String 转 Long
                needDbCheckMap.put(Long.parseLong(vo.getId()), vo);
            }
        }
        
        // 批量查数据库（只查不在缓存中的）
        if (!needDbCheckIds.isEmpty()) {
            Set<Long> likedIds = commentLikeMapper.selectLikedCommentIds(userId, needDbCheckIds);
            for (Long commentId : needDbCheckIds) {
                needDbCheckMap.get(commentId).setIsLiked(likedIds.contains(commentId));
            }
        }
    }

    /**
     * 合并Redis增量点赞数
     * 
     * @param commentVOs 评论VO列表
     */
    private void mergeCommentLikeCounts(List<CommentVO> commentVOs) {
        if (commentVOs == null || commentVOs.isEmpty()) {
            return;
        }

        try {
            // 构建Redis键列表
            List<String> keys = new ArrayList<>();
            for (CommentVO vo : commentVOs) {
                keys.add(COMMENT_LIKE_COUNT_KEY + vo.getId());
            }

            // 批量获取Redis增量
            List<Long> increments = cacheService.batchGetNumbers(keys);

            // 合并到VO
            for (int i = 0; i < commentVOs.size(); i++) {
                CommentVO vo = commentVOs.get(i);
                Long increment = increments.get(i);
                if (increment != null && increment != 0) {
                    vo.setLikeCount(vo.getLikeCount() + increment);
                }
            }
        } catch (Exception e) {
            log.error("合并评论点赞数失败", e);
            // 不抛出异常，继续返回数据库中的点赞数
        }
    }

    /**
     * 从 ZSet 按页码获取评论ID列表（使用 ZRANGE 按索引）
     * 
     * @param zsetKey ZSet的key
     * @param page 页码（从1开始）
     * @param limit 每页数量
     * @return 评论ID集合（按时间由远到近排序）
     */
    private Set<String> getCommentIdsFromZSetByPage(String zsetKey, int page, int limit) {
        try {
            // 计算索引范围
            long start = (long) (page - 1) * limit;
            long end = start + limit - 1;
            
            // 使用 ZRANGE 按索引查询
            return cacheService.zRange(zsetKey, start, end);
        } catch (Exception e) {
            log.error("从ZSet按页码获取评论ID失败: key={}, page={}, limit={}, error={}", 
                     zsetKey, page, limit, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 批量查询根评论（先查Redis缓存，未命中再查数据库）
     * 
     * @param commentIds 评论ID列表
    * @return 根评论列表
     */
    private List<CommentVO> batchGetRootCommentsByIds(List<String> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, CommentVO> resultMap = new LinkedHashMap<>(); // 保持顺序
        List<Long> missedIds = new ArrayList<>(); // 缓存未命中的ID
        
        // 1. 遍历所有ID，先从Redis缓存查询
        for (String commentId : commentIds) {
            String cacheKey = COMMENT_DETAIL_ROOT_KEY + commentId;
            RootCommentBO cachedBO = cacheService.get(cacheKey);
            
            if (cachedBO != null) {
                // 缓存命中，转换为VO
                CommentVO vo = commentParamMapper.boToCommentVO(cachedBO);
                resultMap.put(commentId, vo);
            } else {
                // 缓存未命中，记录ID
                missedIds.add(Long.parseLong(commentId));
            }
        }
        
        // 2. 如果有未命中的，批量从数据库查询
        if (!missedIds.isEmpty()) {
            List<RootCommentBO> bos = commentMapper.selectRootCommentsByIds(missedIds);
            
            // 3. 将数据库查到的数据写回Redis，并加入结果
            for (RootCommentBO bo : bos) {
                String cacheKey = COMMENT_DETAIL_ROOT_KEY + bo.getId();
                cacheService.set(cacheKey, bo, COMMENT_DETAIL_CACHE_TTL, TimeUnit.MINUTES);
                
                CommentVO vo = commentParamMapper.boToCommentVO(bo);
                resultMap.put(String.valueOf(bo.getId()), vo);
            }
            
            log.info("根评论缓存命中:{}, 未命中:{}", commentIds.size() - missedIds.size(), missedIds.size());
        } else {
            log.info("根评论全部缓存命中: {}", commentIds.size());
        }
        
        // 4. 按照原始commentIds的顺序返回
        return commentIds.stream()
                .map(resultMap::get)
                .filter(Objects::nonNull)
                .toList();
    }
    
    /**
     * 批量查询子评论（先查Redis缓存，未命中再查数据库）
     * 
     * @param commentIds 评论ID列表
     * @return 子评论列表
     */
    private List<CommentVO> batchGetChildCommentsByIds(List<String> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, CommentVO> resultMap = new LinkedHashMap<>(); // 保持顺序
        List<Long> missedIds = new ArrayList<>(); // 缓存未命中的ID
        
        // 1. 遍历所有ID，先从Redis缓存查询
        for (String commentId : commentIds) {
            String cacheKey = COMMENT_DETAIL_CHILD_KEY + commentId;
            ChildCommentBO cachedBO = cacheService.get(cacheKey);
            
            if (cachedBO != null) {
                // 缓存命中，转换为VO
                CommentVO vo = commentParamMapper.childBoToCommentVO(cachedBO);
                resultMap.put(commentId, vo);
            } else {
                // 缓存未命中，记录ID
                missedIds.add(Long.parseLong(commentId));
            }
        }
        
        // 2. 如果有未命中的，批量从数据库查询
        if (!missedIds.isEmpty()) {
            List<ChildCommentBO> bos = commentMapper.selectChildCommentsByIds(missedIds);
            
            // 3. 将数据库查到的数据写回Redis，并加入结果
            for (ChildCommentBO bo : bos) {
                String cacheKey = COMMENT_DETAIL_CHILD_KEY + bo.getId();
                cacheService.set(cacheKey, bo, COMMENT_DETAIL_CACHE_TTL, TimeUnit.MINUTES);
                
                CommentVO vo = commentParamMapper.childBoToCommentVO(bo);
                resultMap.put(String.valueOf(bo.getId()), vo);
            }
            
            log.debug("子评论缓存命中:{}, 未命中:{}", commentIds.size() - missedIds.size(), missedIds.size());
        } else {
            log.debug("子评论全部缓存命中: {}", commentIds.size());
        }
        
        // 4. 按照原始commentIds的顺序返回
        return commentIds.stream()
                .map(resultMap::get)
                .filter(Objects::nonNull)
                .toList();
    }
    
    /**
     * 追加根评论到ZSet缓存
     * 
     * @param gifId GIF ID
     * @param rootCommentBOs 根评论BO列表
     */
    private void appendToRootCommentZSet(String gifId, List<RootCommentBO> rootCommentBOs) {
        try {
            String zsetKey = COMMENT_ROOT_KEY + gifId;
            
            // 将评论ID和createdAt作为score添加到ZSet
            Map<String, Double> scoreMembers = new HashMap<>();
            for (RootCommentBO bo : rootCommentBOs) {
                double score = bo.getCreatedAt().atZone(ZoneId.systemDefault()).toEpochSecond();
                scoreMembers.put(String.valueOf(bo.getId()), score);
            }
            
            // 批量添加到ZSet
            cacheService.zsetAddBatch(zsetKey, scoreMembers);
            
            // 设置过期时间
            cacheService.expire(zsetKey, ZSET_CACHE_TTL, TimeUnit.MINUTES);
            
            log.info("追加根评论到ZSet: gifId={}, count={}", gifId, rootCommentBOs.size());
        } catch (Exception e) {
            log.error("追加根评论到ZSet失败: gifId={}, error={}", gifId, e.getMessage(), e);
        }
    }
    
    /**
     * 追加子评论到ZSet缓存
     */
    private void appendToChildCommentZSet(String rootCommentId, List<ChildCommentBO> childCommentBOs) {
        try {
            String zsetKey = COMMENT_CHILD_KEY + rootCommentId;
            
            // 将评论ID和createdAt作为score添加到ZSet
            Map<String, Double> scoreMembers = new HashMap<>();
            for (ChildCommentBO bo : childCommentBOs) {
                double score = bo.getCreatedAt().atZone(ZoneId.systemDefault()).toEpochSecond();
                scoreMembers.put(String.valueOf(bo.getId()), score);
            }
            
            // 批量添加到ZSet
            cacheService.zsetAddBatch(zsetKey, scoreMembers);
            
            // 设置过期时间
            cacheService.expire(zsetKey, ZSET_CACHE_TTL, TimeUnit.MINUTES);
            
            log.info("追加子评论到ZSet: rootId={}, count={}", rootCommentId, childCommentBOs.size());
        } catch (Exception e) {
            log.error("追加子评论到ZSet失败: rootId={}, error={}", rootCommentId, e.getMessage(), e);
        }
    }
    
    /**
     * 获取GIF根评论列表（游标+页码分页，带ZSet缓存）
     * 
     * <p>查询策略：</p>
     * <ol>
     *   <li>用 page 从 ZSet 按索引查询 (ZRANGE)</li>
     *   <li>ZSet 有数据 → 返回</li>
     *   <li>ZSet 无数据 → 用 cursor 从 DB 查询 → 写回 ZSet</li>
     * </ol>
     * 
     * @param gifId GIF ID
     * @param cursor 游标（createdAt），用于DB查询
     * @param page 页码（从1开始），用于ZSet索引计算
     * @param limit 每次获取数量
     * @param userId 当前用户ID（可为空，用于查询点赞状态）
     * @return 根评论列表（包含热评）
     */
    @Override
    public List<CommentVO> getRootCommentsByCursor(String gifId, LocalDateTime cursor, Integer page, Integer limit, Long userId) {
        if (StrUtil.isBlank(gifId)) {
            throw new IllegalArgumentException("GIF ID不能为空");
        }
        if (cursor == null) {
            throw new IllegalArgumentException("cursor 不能为空");
        }
        if (page == null || page <= 0) {
            page = 1; // 默认第1页
        }
        if (limit == null || limit <= 0) {
            limit = 10; // 默认每页10条
        }
        
        String zsetKey = COMMENT_ROOT_KEY + gifId;
        // 1. 先用 page 从 ZSet 按索引查询（调整查询数量）
        Set<String> commentIdsFromCache = getCommentIdsFromZSetByPage(zsetKey, page, limit);

        List<CommentVO> comments;
        
        if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty()) {
            // ZSet 缓存命中
            comments = batchGetRootCommentsByIds(new ArrayList<>(commentIdsFromCache));
            log.info("根评论ZSet缓存命中: gifId={}, page={}, size={}", gifId, page, comments.size());
        } else {
            // ZSet 未命中（该页没有缓存），使用分布式锁从DB查询
            String lockKey = LOCK_COMMENT_ROOT_KEY + gifId + ":page:" + page;
            boolean locked = cacheService.setIfAbsent(lockKey, "1", LOCK_WAIT_TIME, TimeUnit.SECONDS);
            
            if (locked) {
                try {
                    // 获得锁，用 cursor 从数据库查询
                    List<RootCommentBO> rootCommentBOs = commentMapper.selectRootCommentsByCursor(
                            Long.parseLong(gifId), cursor, limit
                    );
                    
                    if (rootCommentBOs.isEmpty()) {
                        return new ArrayList<>();
                    }
                    
                    // 转换为 CommentVO
                    comments = rootCommentBOs.stream()
                            .map(commentParamMapper::boToCommentVO)
                            .toList();
                    
                    // 写回 ZSet 缓存
                    appendToRootCommentZSet(gifId, rootCommentBOs);
                    
                    log.info("根评论从DB查询并写回ZSet: gifId={}, page={}, cursor={}, size={}", 
                            gifId, page, cursor, comments.size());
                } finally {
                    // 释放锁
                    cacheService.delete(lockKey);
                }
            } else {
                // 未获得锁，多次重试从缓存读取
                comments = retryGetRootCommentsFromCache(zsetKey, gifId, cursor, limit, page);
            }
        }
        
        // 丰富根评论数据（子评论数量、点赞状态）
        enrichRootComments(comments, userId);
        
        return comments;
    }

    /**
     * 获取子评论列表（游标+页码分页，带ZSet缓存）
     * 
     * <p>查询策略：</p>
     * <ol>
     *   <li>用 page 从 ZSet 按索引查询 (ZRANGE)</li>
     *   <li>ZSet 有数据 → 返回</li>
     *   <li>ZSet 无数据 → 用 cursor 从 DB 查询 → 写回 ZSet</li>
     * </ol>
     * 
     * @param rootCommentId 根评论ID
     * @param cursor 游标（createdAt），用于DB查询
     * @param page 页码（从1开始），用于ZSet索引计算
     * @param limit 每次获取数量
     * @param userId 当前用户ID（可为空，用于查询点赞状态）
     * @return 子评论列表
     */
    @Override
    public List<CommentVO> getChildCommentsByCursor(String rootCommentId, LocalDateTime cursor, Integer page, Integer limit, Long userId) {
        if (StrUtil.isBlank(rootCommentId)) {
            throw new IllegalArgumentException("根评论ID不能为空");
        }
        if (cursor == null) {
            throw new IllegalArgumentException("cursor 不能为空");
        }
        if (page == null || page <= 0) {
            page = 1; // 默认第1页
        }
        if (limit == null || limit <= 0) {
            limit = 10; // 默认每页10条
        }
        
        String zsetKey = COMMENT_CHILD_KEY + rootCommentId;
        
        // 1. 先用 page 从 ZSet 按索引查询
        Set<String> commentIdsFromCache = getCommentIdsFromZSetByPage(zsetKey, page, limit);
        
        List<CommentVO> comments;
        
        if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty()) {
            // ZSet 缓存命中
            comments = batchGetChildCommentsByIds(new ArrayList<>(commentIdsFromCache));
            log.info("子评论ZSet缓存命中: rootId={}, page={}, size={}", rootCommentId, page, comments.size());
        } else {
            // ZSet 未命中（该页没有缓存），使用分布式锁从DB查询
            String lockKey = LOCK_COMMENT_CHILD_KEY + rootCommentId + ":page:" + page;
            boolean locked = cacheService.setIfAbsent(lockKey, "1", LOCK_WAIT_TIME, TimeUnit.SECONDS);
            
            if (locked) {
                try {
                    // 获得锁，用 cursor 从数据库查询
                    List<ChildCommentBO> childCommentBOs = commentMapper.selectChildCommentsByCursor(
                            Long.parseLong(rootCommentId), cursor, limit
                    );
                    
                    if (childCommentBOs.isEmpty()) {
                        return new ArrayList<>();
                    }
                    
                    // 转换为 CommentVO
                    comments = childCommentBOs.stream()
                            .map(commentParamMapper::childBoToCommentVO)
                            .toList();
                    
                    // 写回 ZSet 缓存（追加这一页的数据）
                    appendToChildCommentZSet(rootCommentId, childCommentBOs);
                    
                    log.info("子评论从DB查询并写回ZSet: rootId={}, page={}, cursor={}, size={}", 
                            rootCommentId, page, cursor, comments.size());
                } finally {
                    // 释放锁
                    cacheService.delete(lockKey);
                }
            } else {
                // 未获得锁，多次重试从缓存读取
                comments = retryGetChildCommentsFromCache(zsetKey, rootCommentId, cursor, limit, page);
            }
        }
        
        // 丰富子评论数据（点赞状态、点赞数）
        enrichChildComments(comments, userId);
        
        return comments;
    }

    /**
     * 获取热门根评论（带独立缓存）
     * 
     * @param gifId GIF ID
     * @param userId 当前用户ID（可为空，用于查询点赞状态）
     * @return 热门根评论列表（最多3条）
     */
    @Override
    public List<CommentVO> getHotRootComments(String gifId, Long userId) {
        if (StrUtil.isBlank(gifId)) {
            return new ArrayList<>();
        }
        
        String hotKey = COMMENT_HOT_ROOT_KEY + gifId;
        
        try {
            // 1. 先从缓存获取热门评论ID列表
            List<String> hotCommentIds = cacheService.getList(hotKey, String.class);
            
            List<CommentVO> hotComments;
            
            if (hotCommentIds != null && !hotCommentIds.isEmpty()) {
                // 缓存命中，批量查询评论详情
                hotComments = batchGetRootCommentsByIds(hotCommentIds);
                log.info("热门根评论缓存命中: gifId={}, size={}", gifId, hotComments.size());
            } else {
                // 缓存未命中，使用分布式锁避免缓存击穿
                String lockKey = LOCK_COMMENT_HOT_ROOT_KEY + gifId;
                boolean locked = cacheService.setIfAbsent(lockKey, "1", LOCK_WAIT_TIME, TimeUnit.SECONDS);
                
                if (locked) {
                    try {
                        // 获得锁，从数据库查询
                        List<RootCommentBO> hotCommentBOs = commentMapper.selectHotRootComments(
                                Long.parseLong(gifId), 10, 3
                        );
                        
                        if (hotCommentBOs.isEmpty()) {
                            // 即使为空也缓存，避免缓存穿透（设置较短的TTL）
                            cacheService.setList(hotKey, new ArrayList<>(), 5, TimeUnit.MINUTES);
                            return new ArrayList<>();
                        }
                        
                        // 转换为 CommentVO
                        hotComments = hotCommentBOs.stream()
                                .map(commentParamMapper::boToCommentVO)
                                .toList();
                        
                        // 写回热门评论ID列表到缓存
                        List<String> ids = hotCommentBOs.stream()
                                .map(bo -> String.valueOf(bo.getId()))
                                .toList();
                        cacheService.setList(hotKey, ids, HOT_COMMENT_CACHE_TTL, TimeUnit.MINUTES);
                        
                        log.info("热门根评论从DB查询并写回缓存: gifId={}, size={}", gifId, hotComments.size());
                    } finally {
                        // 释放锁
                        cacheService.delete(lockKey);
                    }
                } else {
                    // 未获得锁，多次重试从缓存读取
                    hotComments = retryGetFromCache(hotKey, gifId);
                }
            }
            
            // 丰富数据（子评论数量、点赞状态）
            enrichRootComments(hotComments, userId);
            
            return hotComments;
            } catch (Exception e) {
            log.error("查询热门根评论失败: gifId={}, error={}", gifId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 重试从缓存获取热门根评论（未获得锁时使用）
     * 
     * @param hotKey 缓存key
     * @param gifId GIF ID
     * @return 热门根评论列表
     */
    private List<CommentVO> retryGetFromCache(String hotKey, String gifId) {
        List<String> hotCommentIds = null;
        
        // 重试多次，给获得锁的线程足够时间完成查询和写缓存
        for (int i = 0; i < CACHE_RETRY_TIMES; i++) {
            try {
                Thread.sleep(CACHE_RETRY_INTERVAL);
                hotCommentIds = cacheService.getList(hotKey, String.class);
                
                if (hotCommentIds != null && !hotCommentIds.isEmpty()) {
                    List<CommentVO> hotComments = batchGetRootCommentsByIds(hotCommentIds);
                    log.info("热门根评论第{}次重试成功从缓存获取: gifId={}, size={}", 
                            i + 1, gifId, hotComments.size());
                    return hotComments;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("查询被中断", e);
            }
        }
        
        // 重试多次仍未获取到缓存，直接查DB（降级）
        log.warn("热门根评论重试{}次后仍未命中缓存，降级查询DB: gifId={}", CACHE_RETRY_TIMES, gifId);
        List<RootCommentBO> hotCommentBOs = commentMapper.selectHotRootComments(
                Long.parseLong(gifId), 10, 3
        );
        return hotCommentBOs.stream()
                .map(commentParamMapper::boToCommentVO)
                .toList();
    }
    
    /**
     * 重试从ZSet缓存获取评论（通用方法）
     * 
     * @param zsetKey ZSet缓存key
     * @param entityId 实体ID（gifId或rootCommentId）
     * @param entityType 实体类型（"根评论"或"子评论"）
     * @param page 页码
     * @param limit 查询数量
     * @param batchGetter 批量查询方法（从缓存获取的评论ID列表转换为CommentVO列表）
     * @param fallbackQuery 降级查询方法（缓存未命中时从DB查询）
     * @return 评论列表
     */
    private List<CommentVO> retryGetCommentsFromZSet(
            String zsetKey, 
            String entityId, 
            String entityType,
            int page, 
            int limit,
            Function<List<String>, List<CommentVO>> batchGetter,
            Supplier<List<CommentVO>> fallbackQuery) {
        
        Set<String> commentIdsFromCache;
        
        // 重试多次，给获得锁的线程足够时间完成查询和写缓存
        for (int i = 0; i < CACHE_RETRY_TIMES; i++) {
            try {
                Thread.sleep(CACHE_RETRY_INTERVAL);
                commentIdsFromCache = getCommentIdsFromZSetByPage(zsetKey, page, limit);
                
                if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty()) {
                    List<CommentVO> comments = batchGetter.apply(new ArrayList<>(commentIdsFromCache));
                    log.info("{}第{}次重试成功从ZSet获取: entityId={}, page={}, size={}", 
                            entityType, i + 1, entityId, page, comments.size());
                    return comments;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("查询被中断", e);
            }
        }
        
        // 重试多次仍未获取到缓存，直接查DB（降级）
        log.warn("{}重试{}次后仍未命中ZSet，降级查询DB: entityId={}, page={}", 
                entityType, CACHE_RETRY_TIMES, entityId, page);
        return fallbackQuery.get();
    }
    
    /**
     * 重试从缓存获取根评论（未获得锁时使用）
     * 
     * @param zsetKey ZSet缓存key
     * @param gifId GIF ID
     * @param cursor 游标（createdAt），用于DB查询
     * @param limit 查询数量
     * @param page 页码
     * @return 根评论列表
     */
    private List<CommentVO> retryGetRootCommentsFromCache(String zsetKey, String gifId, LocalDateTime cursor, int limit, int page) {
        return retryGetCommentsFromZSet(
            zsetKey, 
            gifId, 
            "rootComment",
            page, 
            limit,
            this::batchGetRootCommentsByIds,
            () -> {
                List<RootCommentBO> bos = commentMapper.selectRootCommentsByCursor(
                    Long.parseLong(gifId), cursor, limit
                );
                return bos.stream()
                    .map(commentParamMapper::boToCommentVO)
                    .toList();
            }
        );
    }
    
    /**
     * 重试从缓存获取子评论（未获得锁时使用）
     * 
     * @param zsetKey ZSet缓存key
     * @param rootCommentId 根评论ID
     * @param cursor 游标（createdAt），用于DB查询
     * @param limit 查询数量
     * @param page 页码
     * @return 子评论列表
     */
    private List<CommentVO> retryGetChildCommentsFromCache(String zsetKey, String rootCommentId, LocalDateTime cursor, int limit, int page) {
        return retryGetCommentsFromZSet(
            zsetKey, 
            rootCommentId, 
            "childComment",
            page, 
            limit,
            this::batchGetChildCommentsByIds,
            () -> {
                List<ChildCommentBO> bos = commentMapper.selectChildCommentsByCursor(
                    Long.parseLong(rootCommentId), cursor, limit
                );
                return bos.stream()
                    .map(commentParamMapper::childBoToCommentVO)
                    .toList();
            }
        );
    }

}



