package com.mawai.ghgif.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.ghaws.constant.MessageType;
import com.mawai.ghaws.message.GifMessage;
import com.mawai.ghaws.r2.R2FileUtils;
import com.mawai.ghaws.service.MessageService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.GiphyMessage;
import com.mawai.ghgif.annotation.RateLimiter;
import com.mawai.ghgif.constant.RateLimiterType;
import com.mawai.ghgif.modelMapper.GifParamMapper;
import com.mawai.ghgif.dto.GifDTO;
import com.mawai.ghgif.dto.GiphyDTO;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.service.UserNicknameCacheService;
import com.mawai.ghgif.event.GifDeleteEvent;
import com.mawai.ghgif.service.GifProcessService;
import com.mawai.ghgif.vo.GifVO;
import com.mawai.ghmbplus.model.*;
import com.mawai.ghmbplus.service.*;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.concurrent.Semaphore;
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
    private final CommentService commentService;
    private final CacheService cacheService;
    private final UserNicknameCacheService userNicknameCacheService;
    private final MessageService messageService;
    private final UserService userService;
    private final GifAuditService gifAuditService;
    // 注入线程池
    private final Executor fileUploadExecutor;
    // 文件上传并发控制 - 限制同时上传到 R2 的文件数量
    // 避免批量上传时触发 R2 API 限流或占用过多网络带宽
    private static final int FILE_UPLOAD_CONCURRENCY_LIMIT = 100;
    private final Semaphore fileUploadSemaphore = new Semaphore(FILE_UPLOAD_CONCURRENCY_LIMIT);

    // S3客户端实例
    private S3Client s3Client;
    
    @PostConstruct
    private void init() {
        this.s3Client = r2FileUtils.getS3Client();
        initTotalGifCount();
    }
    
    /**
     * 系统启动时初始化 GIF 总数到 Redis
     *
     * <p>从数据库查询状态为 1（正常）的 GIF 总数，并缓存到 Redis 中，
     * 不设置过期时间（永久有效）。如果初始化失败，设置默认值 0。</p>
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
     * 获取 GIF 总数（从 Redis 缓存）
     *
     * <p>优先从 Redis 缓存读取，如果缓存不存在或读取失败，则查询数据库兜底。</p>
     *
     * @return GIF 总数，失败时返回 0
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
     * 减少 GIF 总数（-1）
     *
     * <p>同时减少全局 GIF 总数和用户维度的 GIF 总数缓存。</p>
     *
     * @param userId 用户 ID
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
     * 上传单个 GIF 文件到 R2 对象存储
     *
     * <p>上传文件到 Cloudflare R2，并将元数据发送到 SQS 队列异步处理。
     * 采用分层目录结构存储文件，避免单目录文件过多。</p>
     *
     * <p><b>文件路径格式：</b>{@code gifs/{userId前2位}/{userId}/{uuid}.gif}</p>
     * <p><b>限流策略：</b>每分钟最多 5 次上传</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>上传文件到 R2 存储</li>
     *   <li>封装 GIF 元数据为 {@link GifMessage}</li>
     *   <li>发送消息到 SQS 队列</li>
     *   <li>消费者异步保存到数据库</li>
     * </ol>
     *
     * <p><b>异常处理：</b>如果 SQS 发送失败，会删除已上传的 R2 文件</p>
     *
     * @param gifDTO GIF 上传请求对象
     * @return 文件访问 URL
     * @throws FileUploadException 文件上传失败时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @RateLimiter(permitsPerSecond = (5 / 60.0), bucketCapacity = 5, message = "上传过于频繁，请稍后再试", type = RateLimiterType.UPLOAD)
    public String r2uploadGif(GifDTO gifDTO) throws FileUploadException {
        // 获取请求内容
        MultipartFile file = gifDTO.getFile();
        Long userId = gifDTO.getUserId();
        String title = gifDTO.getTitle();
        String description = gifDTO.getDescription();
        String tags = gifDTO.getTags();

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

            String fileUrl = "https://" + R2FileUtils.CDN_DOMAIN + "/" + gifFileName;
            // 存到审核表
            GifAudit gifAudit = new GifAudit();
            gifAudit.setFileUrl(fileUrl);
            gifAudit.setCreatedAt(LocalDateTime.now());
            gifAudit.setUserId(userId);
            gifAudit.setTitle(title);
            gifAudit.setDescription(description);
            gifAudit.setTags(tags);
            gifAuditService.save(gifAudit);
            return fileUrl;
        } catch (IOException | S3Exception e) {
            log.error("R2文件上传失败: {}", e.getMessage(), e);
            throw new FileUploadException("文件上传失败，请稍后再试");
        } catch (RuntimeException e) {
            // save audit失败，删除r2文件
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
     * 批量上传 GIF 文件
     *
     * <p>使用虚拟线程并发上传多个文件，提高上传效率。
     * 每个文件独立处理，失败不影响其他文件。</p>
     *
     * <p><b>并发策略：</b>使用 {@code fileUploadExecutor} 虚拟线程池并发上传</p>
     * <p><b>限流策略：</b>每个文件独立限流（通过 AOP 代理调用 {@link #r2uploadGif}）</p>
     *
     * @param gifsDTO 文件对象列表
     * @return 文件访问 URL 列表，失败的位置为空字符串
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
                    // 添加 30 秒超时,避免无限等待导致虚拟线程阻塞
                    if (!fileUploadSemaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                        log.error("获取文件上传信号量超时(30s),文件索引: {}", index);
                        exceptionArray[index] = new RuntimeException("获取上传信号量超时");
                        return;
                    }
                    try {
                        // 使用 getAopProxy 获取代理对象，确保 @RateLimiter 切面生效
                        String url = SpringUtils.getAopProxy(this).r2uploadGif(gifDTO);
                        // 按原始索引位置存储结果
                        urlArray[index] = url;
                    } finally {
                        // 释放信号量许可
                        fileUploadSemaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("等待文件上传信号量许可时被中断，文件索引: {}", index);
                    exceptionArray[index] = e;
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
     * 更新 GIF 下载次数
     *
     * <p>增量更新 Redis 计数器，定时任务会批量同步到数据库。</p>
     *
     * @param fileId 文件 ID
     * @return 是否更新成功
     */
    @Override
    public boolean updateDownloadCount(String fileId) {
            // 下载次数+1 并设置过期时间
            cacheService.increment(DOWNLOAD_COUNT_KEY + fileId, 1, EXPIRE_TIME, TimeUnit.MINUTES);
            return true;
    }

    /**
     * 切换 GIF 点赞状态
     *
     * <p>使用 Redis 实现点赞/取消点赞功能，支持用户分类管理。</p>
     *
     * <p><b>点赞操作：</b></p>
     * <ul>
     *   <li>从 dislike set 中删除（如果存在）</li>
     *   <li>添加到 like hash 中，记录分类 ID</li>
     *   <li>增加点赞计数</li>
     * </ul>
     *
     * <p><b>取消点赞操作：</b></p>
     * <ul>
     *   <li>从 like hash 中删除（如果存在）</li>
     *   <li>添加到 dislike set 中</li>
     *   <li>减少点赞计数</li>
     * </ul>
     *
     * @param fileId 文件 ID
     * @param userLikeCategoryId 用户点赞分类 ID（点赞时必填）
     * @param userId 用户 ID
     * @param isLike true-点赞，false-取消点赞
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
     * 删除 GIF 文件（软删除 + 批量物理删除）
     *
     * <p>采用软删除策略，先从数据库删除记录，将文件信息保存到删除表，
     * 达到阈值后触发事件批量删除 R2 存储上的文件。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>查询 GIF 是否存在</li>
     *   <li>保存删除记录到 gif_delete 表</li>
     *   <li>从 gif 表删除记录</li>
     *   <li>减少 GIF 总数缓存</li>
     *   <li>删除计数达到阈值时，发布 {@link GifDeleteEvent} 事件</li>
     *   <li>事件监听器批量删除 R2 文件</li>
     * </ol>
     *
     * <p><b>设计理念：</b>批量删除减少 R2 API 调用次数，降低成本</p>
     *
     * @param fileId 文件 ID
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteGif(String fileId) {
        boolean success;
        // 根据fileId查询fileName
        Gif gif = gifService.getById(fileId);
        if (gif == null) {
            log.warn("GIF文件不存在: {}", fileId);
            return false;
        }

        // 软删除fileId下的所有评论
        boolean updated = commentService.lambdaUpdate()
                .eq(Comment::getGifId, Long.parseLong(fileId))
                .set(Comment::getStatus, 0)
                .set(Comment::getUpdatedAt, LocalDateTime.now())
                .update();
        log.info("软删除GIF文件下的所有评论: {}", updated);

        // 保存删除的文件到删除表中
        GifDelete gifDelete = new GifDelete();
        gifDelete.setFileUrl(gif.getGiphyId());
        gifDelete.setFileId(fileId);
        gifDelete.setCreatedAt(LocalDateTime.now());
        gifDeleteService.save(gifDelete);

        // 删除GIF
        success = gifService.removeById(fileId);
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
    }
    
    /**
     * 分页获取用户上传的 GIF 列表
     *
     * <p>查询用户上传的所有 GIF，并合并 Redis 中的实时统计数据（点赞、下载、查看次数）。</p>
     *
     * <p><b>性能优化：</b>使用 Redis 缓存用户 GIF 总数，避免每次分页都 count</p>
     *
     * @param userId 用户 ID
     * @param page 页码
     * @param pageSize 每页数量
     * @return GIF 列表和总数
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
     * 更新查看次数 -- 每人每分钟最多增加5次浏览量，不管看多少个不同的GIF
     * @param fileId 文件名
     * @return 是否更新成功
     */
    @Override
    @RateLimiter(permitsPerSecond = (5 / 60.0), bucketCapacity = 5, message = "更新查看次数频繁", type = RateLimiterType.VIEW)
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

    // giphy integration

    /**
     * 添加 GIF 到 what we like
     * 发送消息到 SQS，由 GiphyMessageConsumer 异步处理
     *
     * @param giphyDTO GiphyDTO
     * @param userId 用户ID
     * @param categoryId 默认喜欢的分类ID
     */
    @Override
    public void addGifToWhatWeLike(GiphyDTO giphyDTO, Long userId, Long categoryId) {
        // 限制 source 字段长度为 256
        if (giphyDTO.getSource() != null && giphyDTO.getSource().length() > 256) {
            giphyDTO.setSource(giphyDTO.getSource().substring(0, 256));
        }
        
        // 构建 Giphy 消息
        GiphyMessage giphyMessage = GiphyMessage.builder()
                .giphyDTO(giphyDTO)
                .userId(userId)
                .categoryId(categoryId)
                .build();
        
        // 发送消息到 SQS
        messageService.send(JSONUtil.toJsonStr(giphyMessage), SQS_QUEUE_URL, MessageType.GIPHY_MESSAGE);
        
        log.info("已发送 Giphy 消息到 SQS: giphyId={}, userId={}, categoryId={}",
                giphyDTO.getGiphyId(), userId, categoryId);
    }


    /**
     * 更新用户昵称
     *
     * <p>同步更新数据库，异步更新评论缓存中的昵称字段</p>
     *
     * <p><b>限流策略：</b>一周只能修改一次</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>更新 users 表的 nickname 字段</li>
     *   <li>立即返回更新结果</li>
     *   <li>异步查询用户所有评论ID</li>
     *   <li>批量更新 Redis 中 comment:detail:{commentId} 的 nickname 字段</li>
     * </ol>
     *
     * @param userId 用户ID
     * @param nickname 新昵称
     * @return 是否更新成功
     */
    @Override
    @RateLimiter(permitsPerSecond = 1.0 / (7 * 24 * 60 * 60), message = "昵称修改过于频繁，一周只能修改一次", type = RateLimiterType.UPDATE_NICKNAME)
    public boolean updateUserNickname(Long userId, String nickname) {
        boolean updated = userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getNickname, nickname)
                .update();
        
        if (updated) {
            // 更新昵称分片缓存
            userNicknameCacheService.updateNickname(userId, nickname);
            log.info("用户{}昵称已更新为: {}", userId, nickname);
        }
        
        return updated;
    }

}