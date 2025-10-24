package com.mawai.ghgif.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.GifMessage;
import com.mawai.ghgif.annotation.RateLimiter;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.constant.RateLimiterType;
import com.mawai.ghgif.modelMapper.GifParamMapper;
import com.mawai.ghgif.dto.GifDTO;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghgif.event.GifDeleteEvent;
import com.mawai.ghgif.service.GifProcessService;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghgif.util.R2FileUtils;
import com.mawai.ghgif.vo.GifVO;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.GifDelete;
import com.mawai.ghmbplus.model.UserLike;
import com.mawai.ghmbplus.service.GifDeleteService;
import com.mawai.ghmbplus.service.GifService;
import com.mawai.ghmbplus.service.UserLikeService;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GIF处理服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GifProcessServiceImpl implements GifProcessService {

    private final R2FileUtils r2FileUtils;
    private final GifService gifService;
    private final UserLikeService userLikeService;
    private final GifParamMapper gifParamMapper;
    private final GifDeleteService gifDeleteService;
    private final CacheService cacheService;
    private final MessageService messageService;
    // 注入线程池
    private final Executor fileUploadExecutor;
    
    // S3客户端实例
    private S3Client s3Client;
    
    @PostConstruct
    private void init() {
        this.s3Client = r2FileUtils.getS3Client();
        initTotalGifCount();
    }
    
    /**
     * 系统启动时初始化GIF总数到Redis
     */
    private void initTotalGifCount() {
        try {
            // 不管Redis中是否存在，都查询数据库
            LambdaQueryWrapper<Gif> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(Gif::getStatus, 1);
            long count = gifService.count(queryWrapper);
            
            // 设置到Redis，不设置过期时间（永久有效）
            cacheService.set(TOTAL_GIF_COUNT_KEY, count);
            log.info("已初始化GIF总数: {}", count);
        } catch (Exception e) {
            log.error("初始化GIF总数失败: {}", e.getMessage(), e);
            // 失败时尝试设置一个默认值，避免空指针
            cacheService.set(TOTAL_GIF_COUNT_KEY, 0);
        }
    }
    
    /**
     * 获取GIF总数（从Redis缓存）
     * @return GIF总数
     */
    @Override
    public Long getTotalGifCount() {
        try {
            Number countObj = cacheService.getNumber(TOTAL_GIF_COUNT_KEY);
            if (countObj != null) {
                return countObj.longValue();
            } else {
                log.warn("GIF总数缓存有问题，请检查Redis是否正常");
                // 手动查库 -- 兜底
                LambdaQueryWrapper<Gif> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.eq(Gif::getStatus, 1);
                return gifService.count(queryWrapper);
            }
        } catch (Exception e) {
            log.error("获取GIF总数失败: {}", e.getMessage(), e);
            // 失败返回默认
            return 0L;
        }
    }

    /**
     * @param userId 用户ID
     * 减少GIF总数（-1）
     */
    private void decrementTotalGifCount(Long userId) {
        try {
            cacheService.decrement(TOTAL_GIF_COUNT_KEY, 1);
            cacheService.decrement(TOTAL_GIF_COUNT_KEY + ":" + userId, 1, 60, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("减少GIF总数失败: {}", e.getMessage(), e);
        }
    }

    private static final String LIKE_COUNT_KEY = "gif:like:";
    private static final String DOWNLOAD_COUNT_KEY = "gif:download:";
    private static final String VIEW_COUNT_KEY = "gif:view:"; // 查看次数缓存键
    private static final String TOTAL_GIF_COUNT_KEY = "gif:total:count"; // GIF总数缓存键
    private static final int EXPIRE_TIME = 2; // 过期时间
    private static final String GIF_DELETE_COUNT_KEY = "gif:delete:count"; // 删除计数器键
    private static final int DELETE_COUNT_THRESHOLD = 1; // 删除阈值
    private static final String DELETE_TASK_KEY = "gif:delete:task"; // 删除任务键
    private static final String USER_LIKE_CATEGORY_KEY = "user:like:category:"; // 用户分类点赞缓存键
    private static final String USER_DISLIKE_KEY = "user:dislike:"; // 用户不喜欢缓存键

    @Value("${aws.sqs.base-queue-url}")
    private String SQS_QUEUE_URL;

    /**
     * 上传单个GIF文件
     * @param gifDTO GIF请求
     *
     * @return 文件访问URL
     * @throws FileUploadException 文件上传异常
     */
    @Override
    @RateLimiter(permitsPerSecond = (5 / 60.0), bucketCapacity = 5, message = "上传过于频繁，请稍后再试", type = RateLimiterType.UPLOAD)
    public String r2uploadGif(GifDTO gifDTO) throws FileUploadException {
        // 获取请求内容
        MultipartFile file = gifDTO.getFile();
        Long userId = gifDTO.getUserId();
        String title = gifDTO.getTitle();
        String description = gifDTO.getDescription();
        List<String> tags = gifDTO.getTags();

        String gifFileName = null;
        // auto close stream
        try (InputStream inputStream = file.getInputStream()) {

            // 构建文件名
            String fileExtension = ".gif";
            String baseFileName = UUID.randomUUID().toString();

            // 创建基于用户ID的目录结构
            String userIdStr = String.valueOf(userId);
            String firstLevel = userIdStr.length() >= 2 ? userIdStr.substring(0, 2) : userIdStr;
            String folder = "gifs/" + firstLevel + "/" + userId + "/";
            gifFileName = folder + baseFileName + fileExtension;

            // 创建PutObjectRequest
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(R2FileUtils.BUCKET_NAME)
                    .key(gifFileName)
                    .contentType(file.getContentType())
                    .build();

            // 上传文件 - 使用成员变量s3Client
            PutObjectResponse response = s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, file.getSize()));
            log.info("文件上传成功: {}", response);

            // 构建GIF消息
            String fileUrl = "https://" + R2FileUtils.CDN_DOMAIN + "/" + gifFileName;
            GifMessage gifMessage = GifMessage.builder()
                    .userId(userId)
                    .title(StringUtils.hasText(title) ? title : file.getOriginalFilename())
                    .fileUrl(fileUrl)
                    .description(description)
                    .tags(tags)
                    .fileSize((int) (file.getSize() / 1024))
                    .build();

            // 发送GIF消息到SQS
            messageService.send(JSONUtil.toJsonStr(gifMessage), SQS_QUEUE_URL, MessageType.GIF_MESSAGE);
            return fileUrl;
        } catch (IOException | S3Exception e) {
            log.error("R2文件上传失败: {}", e.getMessage(), e);
            throw new FileUploadException("文件上传失败，请稍后再试");
        } catch (RuntimeException e) {
            // 发送GIF消息到SQS失败，删除r2文件
            if (gifFileName != null) {
                s3Client.deleteObject(
                        DeleteObjectRequest.builder()
                                .bucket(R2FileUtils.BUCKET_NAME)
                                .key(gifFileName)
                                .build()
                );
            }
            throw e;
        }
    }

    /**
     * 批量上传GIF文件
     * @param gifsDTO 文件对象列表
     *
     * @return 文件访问URL列表，失败的位置为空字符串
     */
    @Override
    public List<String> r2batchUploadGif(List<GifDTO> gifsDTO) {
        int fileCount = gifsDTO.size();
        // 创建固定大小的结果数组，保持顺序
        String[] urlArray = new String[fileCount];
        Exception[] exceptionArray = new Exception[fileCount];
        
        // 创建并行任务
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < gifsDTO.size(); i++) {
            final int index = i;
            GifDTO gifDTO = gifsDTO.get(i);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // 使用 getAopProxy 获取代理对象，确保 @RateLimiter 切面生效
                    String url = SpringUtils.getAopProxy(this).r2uploadGif(gifDTO);
                    // 按原始索引位置存储结果
                    urlArray[index] = url;
                } catch (Exception e) {
                    // 记录异常但不中断其他上传，保持与文件相同的索引位置
                    exceptionArray[index] = e;
                }
            }, fileUploadExecutor);

            futures.add(future);
        }
        
        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        // 遍历异常打印log
        for (int i = 0; i < fileCount; i++) {
            if (exceptionArray[i] != null) {
                log.error("上传第{}个GIF文件失败: {}", i + 1, exceptionArray[i].getMessage());
            }
        }
        
        // 转换为List返回，保持原始顺序
        List<String> urls = new ArrayList<>();
        for (String url : urlArray) {
            urls.add(url != null ? url : "");
        }
        return urls;
    }

    

    /**
     * 更新下载次数
     * @param fileId 文件Id
     * @return 是否更新成功
     */
    @Override
    public boolean updateDownloadCount(String fileId) {
            // 下载次数+1 并设置过期时间
            cacheService.increment(DOWNLOAD_COUNT_KEY + fileId, 1, EXPIRE_TIME, TimeUnit.MINUTES);
            return true;
    }

    /**
     * 更新点赞次数 - 使用redis实现
     * @param fileId 文件ID
     * @param userLikeCategoryId 用户点赞分类ID
     * @param userId 用户ID
     * @param isLike 点赞还是取消点赞
     * @return 是否更新成功
     */
    @Override
    public boolean toggleGifLike(String fileId, Long userLikeCategoryId, Long userId, Boolean isLike) {
        try {
            // 创建redisKey
            String countKey = LIKE_COUNT_KEY + fileId;
            String likeHashKey = USER_LIKE_CATEGORY_KEY + userId;
            String dislikeSetKey = USER_DISLIKE_KEY + userId;
            
            if (isLike) {
                // 验证分类ID不能为空
                if (userLikeCategoryId == null) {
                    log.error("点赞时分类ID不能为空: fileId={}, userId={}", fileId, userId);
                    return false;
                }
                
                // 点赞操作：检查dislike set，有则删除；无论如何都在like hash中设置
                cacheService.likeOperationOptimized(countKey, likeHashKey, dislikeSetKey, fileId, userLikeCategoryId, EXPIRE_TIME, TimeUnit.MINUTES);
                
                log.info("用户{}对GIF{}点赞成功，分类ID: {}", userId, fileId, userLikeCategoryId);
            } else {
                // 取消点赞操作：检查like hash，有则删除；没有则在dislike set中标记
                cacheService.dislikeOperationOptimized(countKey, likeHashKey, dislikeSetKey, fileId, EXPIRE_TIME, TimeUnit.MINUTES);
                
                log.info("用户{}对GIF{}取消点赞成功", userId, fileId);
            }
            return true;
        } catch (Exception e) {
            // 记录日志
            log.error("更新点赞次数失败: fileId={}, userId={}, isLike={}, categoryId={}, 错误: {}", 
                     fileId, userId, isLike, userLikeCategoryId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 删除GIF文件 -- 数据库层面删除
     * 1.先查询文件是否存在
     * 2.如果存在，则先保存删除的文件到删除表中 -- 删除表监听删除事件（定时轮询也可能会浪费请求一次的资源，比如每小时轮询一次但是只有一个删除图片）
     * 3.如果删除成功，则减少GIF总数
     * 这样做是为了减少删除文件的次数，减少R2的请求次数，
     *
     * @param fileId 文件ID
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteGif(String fileId) {
        Gif gif;
        try {
            // 根据fileId查询fileName
            gif = gifService.getById(fileId);
            if (gif == null) {
                log.warn("GIF文件不存在: {}", fileId);
                return false;
            }

            // 保存删除的文件到删除表中
            GifDelete gifDelete = new GifDelete();
            gifDelete.setFileUrl(gif.getFileUrl());
            gifDelete.setFileId(fileId);
            gifDelete.setCreatedAt(LocalDateTime.now());
            gifDeleteService.save(gifDelete);

            // 删除GIF
            boolean success = gifService.removeById(fileId);
            // TODO 如果删除成功，减少GIF总数 -- 后期转移到Audit模块
            if (success) {
                decrementTotalGifCount(gif.getUserId());
                Long incremented = cacheService.increment(GIF_DELETE_COUNT_KEY, 1);
                if (incremented % DELETE_COUNT_THRESHOLD == 0) {
                    boolean acquired = cacheService.setIfAbsent(DELETE_TASK_KEY, "1", 5, TimeUnit.MINUTES);
                    if (acquired) {
                        try {
                            // 发布事件
                            SpringUtils.context().publishEvent(
                                    // 每次清理2倍的数量确保都清理（因为handleProcessingFailure方法也会insert）也减少调用client的次数
                                    new GifDeleteEvent().setDelCount(incremented).setBatchSize(DELETE_COUNT_THRESHOLD * 2)
                            );
                        } finally {
                            // 释放锁
                            cacheService.delete(DELETE_TASK_KEY);
                        }
                    }
                }
            }

            return success;
        } catch (Exception e) {
            log.error("删除GIF文件失败: {}", e.getMessage());
            return false; // 删除失败就返回
        }
    }
    
    /**
     * 获取用户上传的GIF文件列表
     * @param userId 用户ID
     * @param page 页码
     * @param pageSize 每页数量
     * @return GIF文件URL列表
     */
    @Override
    public Pair<List<GifVO>, Long> listGifsByUser(Long userId, Integer page, Integer pageSize) {
        // 创建分页对象
        Page<Gif> pageParam = new Page<>(page, pageSize);
        // 先查redis看是否有这个用户上传gif数量的统计
        String countKey = TOTAL_GIF_COUNT_KEY + ":" + userId;
        Number countObject = cacheService.getNumber(countKey);
        // 如果redis有这个用户上传gif数量的统计，则设置total，跳过searchCount, 如果数量发生变化，这个redis会自动更新, 如redisKey过期，则重新查询数据库
        if (countObject != null) {
            pageParam.setSearchCount(false);
            pageParam.setTotal(countObject.longValue());
        }
        // 查询指定用户上传的GIF
        LambdaQueryWrapper<Gif> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Gif::getUserId, userId).eq(Gif::getStatus, 1).orderByDesc(Gif::getCreatedAt);

        // 执行分页查询
        Page<Gif> resultPage = gifService.page(pageParam, queryWrapper);
        List<Gif> gifList = resultPage.getRecords();

        // 如果redis没有这个用户上传gif数量的统计
        if (countObject == null) {
            cacheService.set(countKey, resultPage.getTotal(), Duration.ofMinutes(60));
        }

        // 转换为VO
        List<GifVO> resList = new ArrayList<>();
        for (Gif gif : gifList) {
            resList.add(gifParamMapper.toGifVO(gif));
        }
        
        // 合并所有实时数量
        mergeAllRealTimeCounts(resList);

        return new ImmutablePair<>(resList, resultPage.getTotal());
    }

    /**
     * 按分类分页获取用户喜欢列表 -- 性能比较差
     * 
     * <p>该方法采用缓存+数据库的混合查询策略来保证数据完整性和一致性：</p>
     * <ul>
     *   <li><strong>获取dislike集合：</strong>从Redis获取用户不喜欢的gif ID集合（当前+备份）</li>
     *   <li><strong>缓存查询：</strong>从Redis的userLikeCategoryKey（Hash结构）中过滤指定分类的gif（缓存中like/dislike互斥）</li>
     *   <li><strong>数据库查询：</strong>从UserLike表中查询对应用户和分类的记录，并排除dislike集合中的gif</li>
     *   <li><strong>数据合并：</strong>合并缓存和数据库数据，去重处理</li>
     *   <li><strong>分页处理：</strong>对合并后的数据进行分页</li>
     * </ul>
     * 
     * <p><strong>核心逻辑：</strong>缓存中的like和dislike是互斥的，但数据库可能包含已被用户取消喜欢的旧记录，
     * 因此在数据库查询时必须排除dislike集合中的gif ID，确保结果的准确性。</p>
     * 
     * @param userId 用户ID
     * @param categoryId 分类ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 用户喜欢列表
     */
    @Override
    public List<GifVO> listUserLikes(Long userId, Long categoryId, Integer pageNum, Integer pageSize) {
        try {
            // 1. 获取用户不喜欢的gif ID集合（用于在数据库查询时排除）
            Set<String> dislikedGifIds = getDislikedGifIds(userId);

            // 2.查缓存所有的gifIds的Map
            Map<String, Long> cacheGifIdsMap = getCacheGifIds(userId);
            
            // 3. 从数据库中查询指定分类的gif ID集合（排除dislike的）
            Set<Long> dbGifIds = getDbGifIdsByCategory(userId, categoryId, dislikedGifIds);

            // 4.遍历cacheGifIdsMap，如果key在dbGifIds中并且value不等于categoryId，则删除dbGifIds中的value
            for (Map.Entry<String, Long> entry : cacheGifIdsMap.entrySet()) {
                if (dbGifIds.contains(Long.parseLong(entry.getKey())) && !entry.getValue().equals(categoryId)) {
                    dbGifIds.remove(Long.parseLong(entry.getKey()));
                } else if (entry.getValue().equals(categoryId)) {
                    // 如果value等于categoryId，则添加到dbGifIds中 Set去重
                    dbGifIds.add(Long.parseLong(entry.getKey()));
                }
            }
            
            if (dbGifIds.isEmpty()) {
                log.info("用户{}分类{}下没有喜欢的GIF", userId, categoryId);
                return new ArrayList<>();
            }
            
            // 4. 转换为List并排序（按ID倒序，最新的在前面）
            List<Long> sortedGifIds = dbGifIds.stream()
                    .sorted(Collections.reverseOrder())
                    .toList();
            
            // 5. 分页处理
            int startIndex = (pageNum - 1) * pageSize;
            int endIndex = Math.min(startIndex + pageSize, sortedGifIds.size());
            
            if (startIndex >= sortedGifIds.size()) {
                log.info("用户{}分类{}分页超出范围: pageNum={}, pageSize={}, total={}", 
                        userId, categoryId, pageNum, pageSize, sortedGifIds.size());
                return new ArrayList<>();
            }
            
            List<Long> pageGifIds = sortedGifIds.subList(startIndex, endIndex);
            
            // 6. 直接查询状态正常的gif详情（一次数据库请求）
            List<Gif> gifs = getGifsByIdsWithStatus(pageGifIds);

            // 7.转换为VO并合并所有实时数量
            List<GifVO> resList = new ArrayList<>();
            for (Gif gif : gifs) {
                resList.add(gifParamMapper.toGifVO(gif));
            }
            // 合并所有实时数量
            mergeAllRealTimeCounts(resList);
            
            return resList;
        } catch (Exception e) {
            log.error("获取用户{}分类{}喜欢列表失败: {}", userId, categoryId, e.getMessage(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 获取用户不喜欢的gif ID集合（包含当前缓存和备份缓存）
     * @param userId 用户ID
     * @return 不喜欢的gif ID集合
     */
    private Set<String> getDislikedGifIds(Long userId) {
        try {
            String userDislikeKey = USER_DISLIKE_KEY + userId;

            if (!cacheService.hasKey(userDislikeKey)) return new HashSet<>();

            // 获取当前不喜欢集合
            Set<String> currentDisliked = cacheService.getStringSetSafely(userDislikeKey, false);
            Set<String> result = new HashSet<>(currentDisliked);
            
            // 获取备份不喜欢集合
            Set<String> oldDisliked = cacheService.getStringSetSafely(userDislikeKey + ":old", false);
            result.addAll(oldDisliked);
            
            return result;            
        } catch (Exception e) {
            log.error("获取用户{}不喜欢列表失败: {}", userId, e.getMessage(), e);
            return new HashSet<>();
        }
    }

    /**
     * 获取用户指定分类的gif ID集合（包含当前缓存和备份缓存）
     * @param userId 用户ID
     * @return gif ID集合
     */
    private Map<String, Long> getCacheGifIds(Long userId) {
        String userLikeCategoryKey = USER_LIKE_CATEGORY_KEY + userId;
        if (!cacheService.hasKey(userLikeCategoryKey)) return new HashMap<>();
        Map<String, Long> result = cacheService.getLikeCategoryIdsSafely(userLikeCategoryKey, false);
        // oldKey
        Map<String, Long> oldResult = cacheService.getLikeCategoryIdsSafely(userLikeCategoryKey + ":old", false);
        result.putAll(oldResult);
        return result;
    }

    /**
     * 从数据库中获取用户指定分类的gif ID集合（排除不喜欢的gif）
     * @param userId 用户ID
     * @param categoryId 分类ID
     * @param dislikedGifIds 用户不喜欢的gif ID集合
     * @return gif ID集合
     */
    private Set<Long> getDbGifIdsByCategory(Long userId, Long categoryId, Set<String> dislikedGifIds) {
        try {
            LambdaQueryWrapper<UserLike> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(UserLike::getUserId, userId)
                       .eq(UserLike::getUserLikeCategoryId, categoryId)
                       .select(UserLike::getGifId); // 只查询gif ID字段
            
            // 查询用户喜欢列表
            List<UserLike> userLikes = userLikeService.list(queryWrapper);

            // 如果有不喜欢的gif ID，排除它们
            if (!dislikedGifIds.isEmpty()) {
                // 将String类型的gif ID转换为Long类型
                Set<Long> dislikedGifIdsLong = dislikedGifIds.stream()
                        .map(Long::parseLong)
                        .collect(Collectors.toSet());
                
                // 排除不喜欢的gif ID 并返回
                return userLikes.stream()
                    .map(UserLike::getGifId)
                    .filter(gifId -> !dislikedGifIdsLong.contains(gifId)) // 避免了NOT IN
                    .collect(Collectors.toSet());
            }
            
            // 返回用户喜欢列表
            return userLikes.stream()
                    .map(UserLike::getGifId)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("从数据库获取用户{}分类{}喜欢列表失败: {}", userId, categoryId, e.getMessage(), e);
            return new HashSet<>();
        }
    }
    
    /**
     * 根据ID列表查询状态正常的gif详情（一次数据库请求）
     * @param gifIds gif ID列表
     * @return gif列表
     */
    private List<Gif> getGifsByIdsWithStatus(List<Long> gifIds) {
        try {
            if (gifIds.isEmpty()) return new ArrayList<>();
            LambdaQueryWrapper<Gif> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.in(Gif::getId, gifIds).eq(Gif::getStatus, 1); // 只查询状态正常的gif
            return gifService.list(queryWrapper);
        } catch (Exception e) {
            log.error("根据ID列表查询gif详情失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 判断用户是否喜欢指定的GIF文件
     * 
     * <p>该方法采用双重缓存机制来保证数据一致性和查询性能：</p>
     * <ul>
     *   <li><strong>userLikeCategoryKey</strong>：用户喜欢分类缓存</li>
     *   <li><strong>userDislikeKey</strong>：用户不喜欢缓存</li>
     *   <li><strong>userLikeCategoryKey:old</strong>：用户喜欢分类缓存旧数据</li>
     *   <li><strong>userDislikeKey:old</strong>：用户不喜欢缓存旧数据</li>
     * </ul>
     * 
     * <h4>查询逻辑：</h4>
     * <ol>
     *   <li>优先查询userLikeCategoryKey 和 userDislikeKey 缓存，如果有数据则直接返回</li>
     *   <li>如果userLikeCategoryKey 和 userDislikeKey 缓存都为空，则查询 userLikeCategoryKey:old 和 userDislikeKey:old 缓存，如果有数据则直接返回</li>
     *   <li>如果userLikeCategoryKey:old 和 userDislikeKey:old 缓存都为空，则查询数据库</li>
     * </ol>
     * 
     * <h4>边界情况处理：</h4>
     * <ul>
     *   <li><strong>用户刚点击喜欢：</strong>userLikeCategoryKey 和 userDislikeKey 缓存有数据但数据库可能尚未同步</li>
     *   <li><strong>缓存清理期间：</strong>userLikeCategoryKey 和 userDislikeKey 缓存被清空但数据库正在写入，此时查询 userLikeCategoryKey:old 和 userDislikeKey:old 缓存保证数据连续性</li>
     *   <li><strong>用户连续操作：</strong>userLikeCategoryKey 和 userDislikeKey 缓存始终保持最新状态，确保实时响应</li>
     * </ul>
     *
     * @param fileId GIF文件的唯一标识符
     * @param userId 用户的唯一标识符
     * @return {@code true} 用户喜欢该GIF，{@code false} 用户不喜欢该GIF
     * @see #toggleGifLike(String, Long, Long, Boolean) 更新用户点赞状态方法
     */
    @Override
    public boolean isLikeThis(String fileId, Long userId) {
        String userLikeCategoryKey = USER_LIKE_CATEGORY_KEY + userId; // 喜欢hashKey
        String userDislikeKey = USER_DISLIKE_KEY + userId; // 不喜欢setKey

        // 先查询缓存
        Map<String, Long> likeCategoryHash = cacheService.getLikeCategoryIdsSafely(userLikeCategoryKey, false);
        Set<String> disLikeSet = cacheService.getStringSetSafely(userDislikeKey, false);

        // key不存在会返回空的数据结构比如空map或者空set
        if (!likeCategoryHash.isEmpty() && likeCategoryHash.containsKey(fileId)) {
            return true;
        } else if (!disLikeSet.isEmpty() && disLikeSet.contains(fileId)) {
            return false;
        } else {
            // 查询oldKey缓存
            Map<String, Long> oldLikeCategoryHash = cacheService.getLikeCategoryIdsSafely(userLikeCategoryKey + ":old", false);
            Set<String> oldDisLikeSet = cacheService.getStringSetSafely(userDislikeKey + ":old", false);

            if (!oldLikeCategoryHash.isEmpty() && oldLikeCategoryHash.containsKey(fileId)) {
                return true;
            } else if (!oldDisLikeSet.isEmpty() && oldDisLikeSet.contains(fileId)) {
                return false;
            } else {
                // 最后查询数据库
                return userLikeService.existsByUserIdAndGifId(userId, Long.parseLong(fileId));
            }
        }
    }

    /**
     * 随机获取GIF列表
     * 原理：随机获取一条数据然后从这一条开始分页查询
     * @param lastId 最后一条数据的ID
     * 
     * @return 随机GIF列表
     * @see #getRandomGif() 获取随机数据方法
     */
    @Override
    public List<GifVO> getRandomGifs(String lastId) {
        Long rId;
        // 如果lastId为空，则随机获取一条数据
        if (lastId == null || lastId.isEmpty()) {
            // 随机获取一条数据
            Gif gif = gifService.getRandomOne();
            rId = gif.getId();
        } else rId = Long.parseLong(lastId);

        if (rId == null) throw new RuntimeException("随机Id为空");
        
        // 从这一条开始查询10个 直接limit 10
        List<Gif> gifList = gifService.list(new LambdaQueryWrapper<Gif>()
                .eq(Gif::getStatus, 1)
                .gt(Gif::getId, rId)
                .orderByAsc(Gif::getId)
                .last("limit 10"));

        // 如果数量小于10 也就是说取的是后10条数据，那么差几条就从库中前几条补
        if (gifList.size() < 10) {
            // 从数据库中获取前10 - gifList.size() 条数据
            List<Gif> gifList2 = gifService.list(new LambdaQueryWrapper<Gif>()
                    .eq(Gif::getStatus, 1)
                    .orderByAsc(Gif::getId)
                    .last("limit " + (10 - gifList.size())));
            gifList.addAll(gifList2);
        }

        // 转换为VO
        List<GifVO> resList = new ArrayList<>();
        for (Gif gif : gifList) {
            resList.add(gifParamMapper.toGifVO(gif));
        }
        
        // 合并所有实时数量
        mergeAllRealTimeCounts(resList);
        return resList;
    }

    /**
     * 随机获取一条数据
     * @return 随机GIF
     */
    @Override
    public GifVO getRandomGif() {
        // 随机获取一条数据
        GifVO gifVO = gifParamMapper.toGifVO(gifService.getRandomOne());
        // 合并所有实时数量
        mergeAllRealTimeCounts(List.of(gifVO));
        return gifVO;
    }

    /**
     * 更新查看次数 -- 每人每分钟对于一个GIF文件的查看次数最多为3次超过3次的不计数
     * @param fileId 文件名
     * @return 是否更新成功
     */
    @Override
    @RateLimiter(permitsPerSecond = (3 / 60.0), bucketCapacity = 3, message = "更新查看次数频繁", type = RateLimiterType.VIEW, businessKeyParamName = "fileId")
    public boolean updateViewCount(String fileId) {
        // 查看次数+1 并设置过期时间
        cacheService.increment(VIEW_COUNT_KEY + fileId, 1, EXPIRE_TIME, TimeUnit.MINUTES);
        return true;
    }

    /**
     * 合并所有实时数量（点赞、查看、下载）
     * 
     * @param gifVOList GIF列表
     */
    private void mergeAllRealTimeCounts(List<GifVO> gifVOList) {
        if (gifVOList.isEmpty()) return;
        
        try {
            CountType[] types = CountType.values();
            
            // 构建所有需要查询的Redis键（按类型分组）
            List<String> allKeys = new ArrayList<>();
            for (CountType type : types) {
                for (GifVO gifVO : gifVOList) {
                    allKeys.add(type.getKeyPrefix() + gifVO.getId());
                }
            }
            
            // 批量获取所有增量数据
            List<Long> allIncrements = cacheService.batchGetNumbers(allKeys);
            
            // 按类型处理数据
            int typeCount = types.length;
            int gifCount = gifVOList.size();
            
            for (int typeIndex = 0; typeIndex < typeCount; typeIndex++) {
                CountType type = types[typeIndex];
                int startIndex = typeIndex * gifCount;
                
                for (int gifIndex = 0; gifIndex < gifCount; gifIndex++) {
                    try {
                        Long increment = allIncrements.get(startIndex + gifIndex);
                        if (increment != null && increment != 0) {
                            GifVO gifVO = gifVOList.get(gifIndex);
                            long currentValue = Optional.ofNullable(type.getGetter().apply(gifVO)).orElse(0L);
                            type.getSetter().accept(gifVO, Math.max(0, currentValue + increment));
                        }
                    } catch (Exception e) {
                        log.warn("批量合并实时数量失败: fileId={}, type={}, 错误: {}",
                                gifVOList.get(gifIndex).getId(), type.getKeyPrefix(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("批量合并所有实时数量失败: {}", e.getMessage());
        }
    }

    /**
     * GIFvo获取实时数量枚举，如果有新增的实时数量，请添加枚举
     */
    @Getter
    private enum CountType {
        LIKE(LIKE_COUNT_KEY, GifVO::getLikeCount, GifVO::setLikeCount),
        VIEW(VIEW_COUNT_KEY, GifVO::getViewCount, GifVO::setViewCount),
        DOWNLOAD(DOWNLOAD_COUNT_KEY, GifVO::getDownloadCount, GifVO::setDownloadCount);

        private final String keyPrefix;
        private final Function<GifVO, Long> getter;
        private final BiConsumer<GifVO, Long> setter;

        CountType(String keyPrefix, Function<GifVO, Long> getter, BiConsumer<GifVO, Long> setter) {
            this.keyPrefix = keyPrefix;
            this.getter = getter;
            this.setter = setter;
        }
    }

    /**
     * 根据GIF ID查询单个GIF详情
     * 
     * @param gifId GIF的ID
     * @return GIF详情，如果不存在则返回null
     */
    @Override
    public GifVO getGifById(Long gifId) {
        // 查询GIF
        Gif gif = gifService.getById(gifId);
        
        if (gif == null) {
            return null;
        }
        
        // 检查状态：只返回正常状态的GIF
        if (gif.getStatus() != 1) {
            return null;
        }
        
        // 转换为VO
        GifVO gifVO = gifParamMapper.toGifVO(gif);
        
        // 合并所有实时数量（点赞、查看、下载）
        mergeAllRealTimeCounts(List.of(gifVO));
        
        return gifVO;
    }

}