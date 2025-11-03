package com.mawai.ghgif.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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
import com.mawai.ghmbplus.dto.ChildCommentBO;
import com.mawai.ghmbplus.dto.ChildCountBO;
import com.mawai.ghmbplus.dto.CommentDetailCacheBO;
import com.mawai.ghmbplus.dto.CommentLikeBO;
import com.mawai.ghmbplus.dto.RootCommentBO;
import com.mawai.ghmbplus.model.Comment;
import com.mawai.ghmbplus.service.CommentService;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 评论处理服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentProcessServiceImpl implements CommentProcessService {

    private final CommentService commentService;
    private final CommentMapper commentMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final CommentParamMapper commentParamMapper;
    private final CacheService cacheService;
    private final MessageService messageService;
    
    private static final String COMMENT_LIKE_COUNT_KEY = "comment:like:"; // 评论点赞数缓存key
    private static final String USER_COMMENT_LIKE_KEY = "user:comment:like:"; // 用户评论点赞缓存key
    private static final String USER_COMMENT_DISLIKE_KEY = "user:comment:dislike:"; // 用户评论取消点赞缓存key
    private static final String COMMENT_DETAIL_KEY = "comment:detail:"; // 评论详情缓存key（Hash结构，统一根评论和子评论）
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
     * @return 包含预生成评论ID的CommentVO
     */
    @Override
    @RateLimiter(permitsPerSecond = (3 / 60.0), bucketCapacity = 3, message = "评论过于频繁", type = RateLimiterType.COMMENT)
    public CommentVO addComment(CommentDTO commentDTO, Long userId) {
        // 参数校验
        if (commentDTO == null) {
            throw new IllegalArgumentException("参数不能为空");
        }
        if (StrUtil.isBlank(commentDTO.getContent())) {
            throw new IllegalArgumentException("评论内容不能为空");
        }

        // 1. 如果是回复评论，校验父评论是否存在且未删除
        // 注意：由于删除根评论会级联删除所有子评论，所以只需检查父评论状态即可 -- 也就是把所有涉及到删除的评论的status都set为0
        if (StrUtil.isNotBlank(commentDTO.getParentId())) {
            Long parentId = Long.parseLong(commentDTO.getParentId());
            Comment parentComment = commentService.lambdaQuery()
                .select(Comment::getId, Comment::getStatus)
                .eq(Comment::getId, parentId)
                .one();
            
            if (parentComment == null) {
                throw new IllegalArgumentException("评论不存在");
            }
            if (parentComment.getStatus() != 1) {
                throw new IllegalArgumentException("评论已被删除");
            }
            
            log.info("用户{}回复评论{}，校验通过", userId, commentDTO.getParentId());
        }

        // 2. 预生成评论ID（使用雪花算法）
        Long commentId = IdWorker.getId();

        // 3. 构建CommentMessage
        CommentMessage commentMessage = new CommentMessage();
        commentMessage.setCommentId(commentId);  // ✅ 设置预生成的ID
        commentMessage.setUserId(userId);
        commentMessage.setGifId(commentDTO.getGifId());
        commentMessage.setContent(commentDTO.getContent().trim());
        commentMessage.setParentId(commentDTO.getParentId());

        // 4. 发送到SQS
        try {
            messageService.send(
                JSONUtil.toJsonStr(commentMessage),
                SQS_QUEUE_URL,
                MessageType.COMMENT_MESSAGE
            );
            log.info("评论消息发送成功: userId={}, commentId={}, gifId={}", 
                    userId, commentId, commentDTO.getGifId());
        } catch (Exception e) {
            log.error("评论消息发送失败: userId={}, commentId={}, gifId={}, error={}", 
                    userId, commentId, commentDTO.getGifId(), e.getMessage(), e);
            throw new RuntimeException("评论提交失败，请稍后重试", e);
        }
        
        // 5. 返回包含ID的CommentVO（简化版本，只包含id）
        CommentVO commentVO = new CommentVO();
        commentVO.setId(String.valueOf(commentId));
        return commentVO;
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
        List<ChildCountBO> childCountBoList = commentMapper.countChildCommentsBatch(rootCommentIds);
        // toMap 查询速度快
        Map<Long, Integer> childCountMap = childCountBoList.stream()
                .collect(Collectors.toMap(ChildCountBO::getRootCommentId, ChildCountBO::getChildCount));
        
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
     * 删除评论（软删除，根评论级联删除子评论）
     * 
     * <p>删除策略</p>
     * <ul>
     *   <li>软删除：设置 status=0</li>
     *   <li>级联删除：删除根评论时，所有子评论（root_comment_id = 根评论ID）也会被删除</li>
     *   <li>子评论删除：删除子评论时，其他回复它的子评论不受影响（只看root_comment_id）</li>
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
        // 将String ID转换为Long ID
        Long commentIdLong = Long.parseLong(commentId);

        Comment comment = commentService.getById(commentIdLong);
        if (comment == null) {
            throw new RuntimeException("评论不存在");
        }

        // 权限校验：只能删除自己的评论
        if (!comment.getUserId().equals(userId)) {
            throw new RuntimeException("无权删除他人评论");
        }

        // 判断是否为根评论
        boolean isRootComment = comment.getRootCommentId() == null;
        
        if (isRootComment) {
            // 根评论：直接根据root_comment_id级联软删除所有子评论
            commentService.lambdaUpdate()
                .eq(Comment::getRootCommentId, commentIdLong)
                .set(Comment::getStatus, 0)
                .update();
            
            log.info("根评论{}被删除，级联软删除了所有子评论", commentId);
        }
        
        // 软删除当前评论
        comment.setStatus((byte) 0);
        boolean result = commentService.updateById(comment);
        
        if (result) {
            // 清理缓存
            if (isRootComment) {
                // 根评论
                // 1.GIF的根评论ZSet 从 comment:root:{gifId} 中 ZREM 该评论ID
                String rootZsetKey = COMMENT_ROOT_KEY + comment.getGifId();
                cacheService.zsetRemove(rootZsetKey, commentId);
                // 2.热门根评论列表（List类型，直接删除整个列表）
                String hotRootKey = COMMENT_HOT_ROOT_KEY + comment.getGifId();
                cacheService.delete(hotRootKey);
                // 3.根评论详情缓存
                String detailKey = COMMENT_DETAIL_KEY + commentId;
                cacheService.delete(detailKey);
                // 4.清理根评论的所有子评论
                String childZsetKey = COMMENT_CHILD_KEY + commentId;
                cacheService.delete(childZsetKey);
                // 5. 子评论详情缓存不删除，让其自然过期（60分钟）
            } else {
                // 子评论
                // 1.子评论ZSet 从 comment:child:{rootCommentId} 中 ZREM 该评论ID
                String childZsetKey = COMMENT_CHILD_KEY + comment.getRootCommentId();
                cacheService.zsetRemove(childZsetKey, commentId);
                // 2.子评论详情缓存
                String detailKey = COMMENT_DETAIL_KEY + commentId;
                cacheService.delete(detailKey);
            }
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
        
        // 1. 从Redis查询用户当前点赞状态（likeSet和dislikeSet）
        String likeSetKey = USER_COMMENT_LIKE_KEY + userId;
        String dislikeSetKey = USER_COMMENT_DISLIKE_KEY + userId;
        Set<String> likeSet = combineSet(likeSetKey);      // Redis新点赞（未刷DB）
        Set<String> dislikeSet = combineSet(dislikeSetKey); // Redis取消点赞（未刷DB）

        // 2. 从DB查询用户所有点赞的评论ID（按点赞时间倒序，只查ID）
        List<Long> dbLikedIds = commentLikeMapper.selectUserLikedCommentIds(userId);
        
        // 3. 合并有效ID：先加Redis新点赞（最新），再加DB点赞（排除dislikeSet）
        //    使用LinkedHashSet保持插入顺序
        // 3.1 先加入Redis新点赞（优先显示最新的）
        Set<String> validIds = new LinkedHashSet<>(likeSet);
        
        // 3.2 再加入DB点赞（排除Redis中已取消点赞的）
        for (Long id : dbLikedIds) {
            String idStr = String.valueOf(id);
            if (!dislikeSet.contains(idStr)) {
                validIds.add(idStr); // LinkedHashSet自动去重
            }
        }
        
        // 4. 内存分页（对合并后的ID列表分页）
        List<String> validIdList = new ArrayList<>(validIds);
        int start = (pageNum - 1) * pageSize;
        int end = Math.min(start + pageSize, validIdList.size());
        
        if (start >= validIdList.size()) {
            log.info("用户{}点赞的评论历史分页超出范围", userId);
            return new ArrayList<>(); // 超出范围，返回空列表
        }
        
        List<String> pagedIds = validIdList.subList(start, end);
        
        // 5. 根据分页后的ID批量查询评论详情
        List<CommentVO> result = batchGetCommentsByIds(pagedIds);
        
        // 6. 合并Redis增量点赞数
        mergeCommentLikeCounts(result);
        
        return result;
    }

    /**
     * 批量查询评论详情（JOIN user表，获取用户昵称和头像）
     * @param commentIds 评论ID列表
     * @return 评论详情列表（已包含用户信息）
     */
    private List<CommentVO> batchGetCommentsByIds(List<String> commentIds) {
        if (commentIds == null || commentIds.isEmpty()) {
            return new ArrayList<>();
        }
        
        // 将String ID转换为Long ID
        List<Long> longIds = commentIds.stream()
                .map(Long::valueOf)
                .toList();
        
        // 查询评论（JOIN user表获取昵称和头像）
        List<CommentLikeBO> bos = commentMapper.selectCommentsByIdsWithUser(longIds);
        
        // 转换为CommentVO（使用已有的 likeBoListToCommentVOList）
        return commentParamMapper.likeBoListToCommentVOList(bos);
    }

    /**
     * 合并set和oldSet
     * @param setKey set的key
     * @return 合并后的set
     */
    private Set<String> combineSet(String setKey) {
        Set<String> set = cacheService.getStringSetSafely(setKey, false);
        Set<String> oldSet = cacheService.getStringSetSafely(setKey + ":old", false);
        if (!oldSet.isEmpty()) {
            set = new HashSet<>(set);
            set.addAll(oldSet);
        }
        return set;
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
     * <p><b>设计前提</b>：前端强制顺序分页，不允许跳页查询</p>
     * 
     * <p><b>工作原理</b>：</p>
     * <ul>
     *   <li>第 1 页查询：ZSet 为空 → 查 DB [1-10] → 写入 ZSet [1-10]</li>
     *   <li>第 2 页查询：ZSet 有 [1-10] → 读取索引 10-19 → 返回空或不足 → 查 DB [11-20] → 追加到 ZSet [1-20]</li>
     *   <li>第 N 页查询：ZSet 逐步累积 [1-N*10]</li>
     * </ul>
     * 
     * <p><b>关键点</b>：调用方必须检测返回数据量是否 < limit，如果不足则查询数据库补充</p>
     * 
     * @param zsetKey ZSet的key
     * @param page 页码（从1开始）
     * @param limit 每页数量
     * @return 评论ID集合（按时间由远到近排序）
     */
    private LinkedHashMap<String, Double> getCommentIdsFromZSetByPage(String zsetKey, int page, int limit) {
        try {
            // 计算索引范围
            // 常规分页就是10条一页，所以这里就是page * 10 - limit
            // 如果limit小于10说明当时请求的是最后一页，这时可能有新评论，所以要从page*10-limit开始查询
            long start = page * 10L - limit;
            long end = start + limit - 1;

            LinkedHashMap<String, Double> res = new LinkedHashMap<>();
            // 使用 zRangeWithScores 分页查询（按 score 升序返回）
            Set<ZSetOperations.TypedTuple<String>> tuples = cacheService.zRangeWithScores(zsetKey, start, end);
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                res.put(tuple.getValue(), tuple.getScore());
            }
            return res;
        } catch (Exception e) {
            log.error("从ZSet按页码获取评论ID失败: key={}, page={}, limit={}, error={}", 
                     zsetKey, page, limit, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 批量查询根评论（先查Redis Hash缓存，未命中再查数据库）
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
        
        // 1. 遍历所有ID，先从Redis Hash缓存查询
        for (String commentId : commentIds) {
            String cacheKey = COMMENT_DETAIL_KEY + commentId;
            Map<String, String> hash = cacheService.hashGetAll(cacheKey);
            
            if (!hash.isEmpty()) {
                // 缓存命中，从Hash解析为BO，再转换为VO
                CommentDetailCacheBO cachedBO = CommentDetailCacheBO.fromHashMap(hash);
                if (cachedBO != null) {
                    CommentVO vo = commentParamMapper.cacheBoToCommentVO(cachedBO);
                    resultMap.put(commentId, vo);
                } else {
                    missedIds.add(Long.parseLong(commentId));
                }
            } else {
                // 缓存未命中，记录ID
                missedIds.add(Long.parseLong(commentId));
            }
        }
        
        // 2. 如果有未命中的，批量从数据库查询
        if (!missedIds.isEmpty()) {
            List<RootCommentBO> bos = commentMapper.selectRootCommentsByIds(missedIds);
            
            // 3. 将数据库查到的数据写回Redis Hash，并加入结果
            for (RootCommentBO bo : bos) {
                // 转换为缓存BO并存储到Hash
                CommentDetailCacheBO cacheBO = CommentDetailCacheBO.fromRoot(bo);
                String cacheKey = COMMENT_DETAIL_KEY + bo.getId();
                cacheService.hashSetAll(cacheKey, cacheBO.toHashMap(), COMMENT_DETAIL_CACHE_TTL, TimeUnit.MINUTES);
                
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
                .collect(Collectors.toList());
    }
    
    /**
     * 批量查询子评论（先查Redis Hash缓存，未命中再查数据库）
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
        
        // 1. 遍历所有ID，先从Redis Hash缓存查询
        for (String commentId : commentIds) {
            String cacheKey = COMMENT_DETAIL_KEY + commentId;
            Map<String, String> hash = cacheService.hashGetAll(cacheKey);
            
            if (!hash.isEmpty()) {
                // 缓存命中，从Hash解析为BO，再转换为VO
                CommentDetailCacheBO cachedBO = CommentDetailCacheBO.fromHashMap(hash);
                if (cachedBO != null) {
                    CommentVO vo = commentParamMapper.cacheBoToCommentVO(cachedBO);
                    resultMap.put(commentId, vo);
                } else {
                    missedIds.add(Long.parseLong(commentId));
                }
            } else {
                // 缓存未命中，记录ID
                missedIds.add(Long.parseLong(commentId));
            }
        }
        
        // 2. 如果有未命中的，批量从数据库查询
        if (!missedIds.isEmpty()) {
            List<ChildCommentBO> bos = commentMapper.selectChildCommentsByIds(missedIds);
            
            // 3. 将数据库查到的数据写回Redis Hash，并加入结果
            for (ChildCommentBO bo : bos) {
                // 转换为缓存BO并存储到Hash
                CommentDetailCacheBO cacheBO = CommentDetailCacheBO.fromChild(bo);
                String cacheKey = COMMENT_DETAIL_KEY + bo.getId();
                cacheService.hashSetAll(cacheKey, cacheBO.toHashMap(), COMMENT_DETAIL_CACHE_TTL, TimeUnit.MINUTES);
                
                CommentVO vo = commentParamMapper.childBoToCommentVO(bo);
                resultMap.put(String.valueOf(bo.getId()), vo);
            }
            
            log.info("子评论缓存命中:{}, 未命中:{}", commentIds.size() - missedIds.size(), missedIds.size());
        } else {
            log.info("子评论全部缓存命中: {}", commentIds.size());
        }
        
        // 4. 按照原始commentIds的顺序返回
        return commentIds.stream()
                .map(resultMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    /**
     * 追加根评论到ZSet缓存 并设置detail_cache（Hash结构）
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
            
            // 批量添加到ZSet 设置过期时间
            cacheService.zsetAddBatch(zsetKey, scoreMembers);
            cacheService.expire(zsetKey, ZSET_CACHE_TTL, TimeUnit.MINUTES);
            
            // 遍历所有ID，设置detail_cache（Hash结构）
            for (RootCommentBO bo : rootCommentBOs) {
                CommentDetailCacheBO cacheBO = CommentDetailCacheBO.fromRoot(bo);
                String cacheKey = COMMENT_DETAIL_KEY + bo.getId();
                cacheService.hashSetAll(cacheKey, cacheBO.toHashMap(), COMMENT_DETAIL_CACHE_TTL, TimeUnit.MINUTES);
            }

            log.info("追加根评论到ZSet: gifId={}, count={}", gifId, rootCommentBOs.size());
        } catch (Exception e) {
            log.error("追加根评论到ZSet失败: gifId={}, error={}", gifId, e.getMessage(), e);
        }
    }
    
    /**
     * 追加子评论到ZSet缓存 并设置detail_cache（Hash结构）
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
            
            // 批量添加到ZSet 设置过期时间
            cacheService.zsetAddBatch(zsetKey, scoreMembers);
            cacheService.expire(zsetKey, ZSET_CACHE_TTL, TimeUnit.MINUTES);

            // 遍历所有ID，设置detail_cache（Hash结构）
            for (ChildCommentBO bo : childCommentBOs) {
                CommentDetailCacheBO cacheBO = CommentDetailCacheBO.fromChild(bo);
                String cacheKey = COMMENT_DETAIL_KEY + bo.getId();
                cacheService.hashSetAll(cacheKey, cacheBO.toHashMap(), COMMENT_DETAIL_CACHE_TTL, TimeUnit.MINUTES);
            }
            
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
     *   <li>ZSet 无数据或数据不充足 → 用 cursor 从 DB 查询（为了防止缓存数据不完整） → 写回 ZSet</li>
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
        
        // 先用 page 从 ZSet 按索引查询
        Map<String, Double> commentIdsFromCache = getCommentIdsFromZSetByPage(zsetKey, page, limit);

        List<CommentVO> comments;
        
        // 优化：检查缓存数据是否足够
        boolean cacheDataInsufficient = (commentIdsFromCache == null || commentIdsFromCache.size() < limit);
        
        if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty() && !cacheDataInsufficient) {
            // ZSet 缓存命中且数据充足
            comments = batchGetRootCommentsByIds(new ArrayList<>(commentIdsFromCache.keySet()));
            log.info("根评论ZSet缓存完全命中: gifId={}, page={}, size={}", gifId, page, comments.size());
        } else {
            // ZSet 未命中或数据可能不充足，从DB查询
            String lockKey = LOCK_COMMENT_ROOT_KEY + gifId + ":page:" + page;
            boolean locked = cacheService.setIfAbsent(lockKey, "1", LOCK_WAIT_TIME, TimeUnit.SECONDS);
            
            if (locked) {
                try {
                    // 获得锁
                    // 用 cursor 从数据库查询 -- 注意：cursor应该是commentIdsFromCache中最后一个
                    // 注意：DB 查询可能返回与缓存重复的数据（因为 cursor 是上一页最后一条的 createdAt）
                    // 但 ZSet 会自动去重（member 相同），所以直接追加即可，如果要从缓存的cursor处开始查询，还需要获取该cursor
                    cursor = getCursor(cursor, commentIdsFromCache);

                    List<RootCommentBO> rootCommentBOs = commentMapper.selectRootCommentsByCursor(
                            Long.parseLong(gifId), cursor, limit
                    );

                    if (rootCommentBOs.isEmpty()) {
                        // DB 没有新数据，说明已到达末尾
                        // 但缓存commentIdsFromCache中可能有数据（最后一页不足 limit 条），需要返回
                        if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty()) {
                            comments = batchGetRootCommentsByIds(new ArrayList<>(commentIdsFromCache.keySet()));
                            log.info("根评论已到末尾，返回缓存中的部分数据: gifId={}, page={}, size={}", 
                                    gifId, page, comments.size());
                        } else {
                            return new ArrayList<>();
                        }
                    } else {
                        // DB 有数据，直接使用（ZSet 会自动去重重复的评论）

                        // 缓存commentIdsFromCache中可能有数据，先查询出来拼到comments中
                        if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty()) {
                            comments = batchGetRootCommentsByIds(new ArrayList<>(commentIdsFromCache.keySet()));
                            comments.addAll(rootCommentBOs.stream()
                                    .map(commentParamMapper::boToCommentVO)
                                    .toList());
                        } else {
                            // 缓存中没有数据
                            comments = rootCommentBOs.stream()
                                    .map(commentParamMapper::boToCommentVO)
                                    .toList();
                        }
                        
                        // 追加到 ZSet 缓存（顺序分页，逐步累积，自动去重）
                        appendToRootCommentZSet(gifId, rootCommentBOs);
                        
                        log.info("根评论从DB查询并追加到ZSet: gifId={}, page={}, cursor={}, size={}", 
                                gifId, page, cursor, comments.size());
                    }
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
     * 获取cursor
     * @param cursor 游标
     * @param commentIdsFromCache 缓存数据
     * @return cursor
     */
    private LocalDateTime getCursor(LocalDateTime cursor, Map<String, Double> commentIdsFromCache) {
        if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty()) {
            // LinkedHashMap 保持插入顺序，直接获取最后一个元素的 score（时间最新的）
            Double lastScore = null;
            for (Double score : commentIdsFromCache.values()) {
                lastScore = score;  // 最后一次循环就是最后一个元素
            }

            if (lastScore != null) {
                // 将 score（秒级时间戳）转换为 LocalDateTime
                cursor = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(lastScore.longValue()),
                        ZoneId.systemDefault()
                );
            }
        }
        return cursor;
    }

    /**
     * 获取子评论列表（游标+页码分页，带ZSet缓存）
     * 
     * <p>查询策略：</p>
     * <ol>
     *   <li>用 page 从 ZSet 按索引查询 (ZRANGE)</li>
     *   <li>ZSet 有数据 → 返回</li>
     *   <li>ZSet 无数据或数据不充足 → 用 cursor 从 DB 查询 → 写回 ZSet</li>
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
        
        // 先用 page 从 ZSet 按索引查询
        Map<String, Double> commentIdsFromCache = getCommentIdsFromZSetByPage(zsetKey, page, limit);
        
        List<CommentVO> comments;
        
        // 优化：检查缓存数据是否足够
        // 如果缓存返回的数据量 < limit，说明 ZSet 数据不完整，需要查询数据库
        boolean cacheDataInsufficient = (commentIdsFromCache == null || commentIdsFromCache.size() < limit);
        
        if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty() && !cacheDataInsufficient) {
            // ZSet 缓存命中且数据充足
            comments = batchGetChildCommentsByIds(new ArrayList<>(commentIdsFromCache.keySet()));
            log.info("子评论ZSet缓存完全命中: rootId={}, page={}, size={}", rootCommentId, page, comments.size());
        } else {
            // ZSet 未命中或数据不足，使用分布式锁从DB查询
            String lockKey = LOCK_COMMENT_CHILD_KEY + rootCommentId + ":page:" + page;
            boolean locked = cacheService.setIfAbsent(lockKey, "1", LOCK_WAIT_TIME, TimeUnit.SECONDS);
            
            if (locked) {
                try {
                    // 获得锁，用 cursor 从数据库查询
                    // 注意：如果缓存有数据，从缓存最后一条的时间开始查询（避免重复数据）
                    cursor = getCursor(cursor, commentIdsFromCache);

                    List<ChildCommentBO> childCommentBOs = commentMapper.selectChildCommentsByCursor(
                            Long.parseLong(rootCommentId), cursor, limit
                    );

                    if (childCommentBOs.isEmpty()) {
                        // DB 没有新数据，说明已到达末尾
                        // 但缓存可能有部分数据（最后一页不足 limit 条），需要返回
                        if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty()) {
                            comments = batchGetChildCommentsByIds(new ArrayList<>(commentIdsFromCache.keySet()));
                            log.info("子评论已到末尾，返回缓存中的部分数据: rootId={}, page={}, size={}", 
                                    rootCommentId, page, comments.size());
                        } else {
                            return new ArrayList<>();
                        }
                    } else {
                        // DB 有数据，直接使用（ZSet 会自动去重重复的评论）

                        // 缓存commentIdsFromCache中可能有数据，先查询出来拼到comments中
                        if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty()) {
                            comments = batchGetChildCommentsByIds(new ArrayList<>(commentIdsFromCache.keySet()));
                            comments.addAll(childCommentBOs.stream()
                                    .map(commentParamMapper::childBoToCommentVO)
                                    .toList());
                        } else {
                            // 缓存中没有数据
                            comments = childCommentBOs.stream()
                                    .map(commentParamMapper::childBoToCommentVO)
                                    .toList();
                        }

                        // 追加到 ZSet 缓存（顺序分页，逐步累积，自动去重）
                        appendToChildCommentZSet(rootCommentId, childCommentBOs);
                        
                        log.info("子评论从DB查询并追加到ZSet: rootId={}, page={}, cursor={}, size={}", 
                                rootCommentId, page, cursor, comments.size());
                    }
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
        List<String> hotCommentIds;
        
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
        
        Map<String, Double> commentIdsFromCache;
        
        // 重试多次，给获得锁的线程足够时间完成查询和写缓存
        for (int i = 0; i < CACHE_RETRY_TIMES; i++) {
            try {
                Thread.sleep(CACHE_RETRY_INTERVAL);
                commentIdsFromCache = getCommentIdsFromZSetByPage(zsetKey, page, limit);
                
                if (commentIdsFromCache != null && !commentIdsFromCache.isEmpty()) {
                    List<CommentVO> comments = batchGetter.apply(new ArrayList<>(commentIdsFromCache.keySet()));
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



