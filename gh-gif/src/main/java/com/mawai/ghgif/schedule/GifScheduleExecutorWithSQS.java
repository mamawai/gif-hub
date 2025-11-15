package com.mawai.ghgif.schedule;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghgif.amazonSQS.consumer.CommentLikesConsumer;
import com.mawai.ghgif.amazonSQS.consumer.UserLikesConsumer;
import com.mawai.ghgif.amazonSQS.message.CommentLikesMessage;
import com.mawai.ghgif.amazonSQS.message.UserLikesMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.event.GifDeleteEvent;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghgif.util.R2FileUtils;
import com.mawai.ghmbplus.dao.CommentMapper;
import com.mawai.ghmbplus.dao.GifMapper;
import com.mawai.ghmbplus.dao.TagMapper;
import com.mawai.ghmbplus.model.*;
import com.mawai.ghmbplus.service.*;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Gif定时任务执行器
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class GifScheduleExecutorWithSQS {

    private final CacheService cacheService;
    private final GifMapper gifMapper;
    private final CommentMapper commentMapper;
    private final GifDeleteService gifDeleteService;
    private final GifDeleteFailedService gifDeleteFailedService;
    private final MessageService messageService;
    private final TagMapper tagMapper;
    private final GifTagService gifTagService;

    // 注入线程池
    private final Executor scheduledExecutor;
    // 虚拟线程执行器，用于数据获取（IO密集型）
    private final Executor virtualDataFetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // 虚拟线程并发控制 - 限制同时处理的用户数据获取任务数量
    // 避免在用户数量过多时创建过多虚拟线程导致内存溢出
    private static final int DATA_FETCH_CONCURRENCY_LIMIT = 300;
    private final Semaphore dataFetchSemaphore = new Semaphore(DATA_FETCH_CONCURRENCY_LIMIT);
    private final R2FileUtils r2FileUtils;

    private static final int SYNC_INTERVAL = 1; // 同步间隔

    private static final String DOWNLOAD_COUNT_KEY = "gif:download:";
    private static final String LIKE_COUNT_KEY = "gif:like:";
    private static final String VIEW_COUNT_KEY = "gif:view:";
    private static final String USER_LIKE_CATEGORY_KEY = "user:like:category:";
    private static final String USER_DISLIKE_KEY = "user:dislike:";
    private final static String HOT_TAG_KEY = "hotTag";
    private static final String COMMENT_LIKE_COUNT_KEY = "comment:like:";
    private static final String COMMENT_DETAIL_KEY = "comment:detail:"; // 评论详情缓存key（Hash结构）
    private static final String USER_COMMENT_LIKE_KEY = "user:comment:like:";
    private static final String USER_COMMENT_DISLIKE_KEY = "user:comment:dislike:";
    private static final int COMMENT_DETAIL_CACHE_TTL = 60; // 评论详情缓存过期时间（分钟）

    @Value("${aws.sqs.base-queue-url}")
    private String SQS_QUEUE_URL;

    @Value("${redis.scanCount}")
    private Integer SCAN_COUNT;

    /**
     * 定时同步 Redis 增量数据到 MySQL
     *
     * <p>每分钟执行一次，将 Redis 中累积的增量统计数据批量同步到数据库。
     * 采用 CompletableFuture 并发执行 6 个同步任务，互不阻塞。</p>
     *
     * <p><b>同步任务列表：</b></p>
     * <ul>
     *   <li>GIF 下载次数 - 直接更新数据库</li>
     *   <li>GIF 查看次数 - 直接更新数据库</li>
     *   <li>GIF 点赞次数 - 直接更新数据库</li>
     *   <li>用户点赞记录 - 发送到 SQS 异步处理</li>
     *   <li>评论点赞次数 - 直接更新数据库</li>
     *   <li>用户评论点赞记录 - 发送到 SQS 异步处理</li>
     * </ul>
     *
     * <p><b>设计理念：</b>高频操作写 Redis（快），定时批量同步到 MySQL（减压）</p>
     *
     * @see #syncDownloadCountToDatabase()
     * @see #syncViewCountsToDatabase()
     * @see #syncLikeCountsToDatabase()
     * @see #syncUserLikesToDatabaseConcurrent()
     * @see #syncCommentLikeCountsToDatabase()
     * @see #syncUserCommentLikesToDatabase()
     */
    @Scheduled(fixedRate = SYNC_INTERVAL * 60 * 1000)
    public void syncGifLikeCount() {
        log.info("开始同步方法...");
        // 使用CompletableFuture并发执行，不等待完成
        CompletableFuture
            .runAsync(this::syncDownloadCountToDatabase, scheduledExecutor)
            .exceptionally(e -> {
                log.error("同步下载计数失败: {}", e.getMessage(), e);
                return null;
            });

        CompletableFuture
            .runAsync(this::syncViewCountsToDatabase, scheduledExecutor)
            .exceptionally(e -> {
                log.error("同步查看计数失败: {}", e.getMessage(), e);
                return null;
            });
            
        CompletableFuture
            .runAsync(this::syncLikeCountsToDatabase, scheduledExecutor)
            .exceptionally(e -> {
                log.error("同步点赞计数失败: {}", e.getMessage(), e);
                return null;
            });
            
        CompletableFuture
            .runAsync(this::syncUserLikesToDatabaseConcurrent, scheduledExecutor)
            .exceptionally(e -> {
                log.error("同步用户喜欢记录失败: {}", e.getMessage(), e);
                return null;
            });
        
        CompletableFuture
            .runAsync(this::syncCommentLikeCountsToDatabase, scheduledExecutor)
            .exceptionally(e -> {
                log.error("同步评论点赞计数失败: {}", e.getMessage(), e);
                return null;
            });
        
        CompletableFuture
            .runAsync(this::syncUserCommentLikesToDatabase, scheduledExecutor)
            .exceptionally(e -> {
                log.error("同步用户评论点赞记录失败: {}", e.getMessage(), e);
                return null;
            });
        
        // 无需等待所有任务完成，直接返回
        log.info("已启动GIF数据同步任务，将在{}分钟后再次触发同步", SYNC_INTERVAL);
    }

    /**
     * 同步 GIF 下载次数到数据库
     *
     * <p>从 Redis 扫描所有下载计数键，提取非零值后批量更新数据库，并原子性重置 Redis 计数。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>扫描 Redis 键：{@code gif:download:*}</li>
     *   <li>过滤非零值并获取计数</li>
     *   <li>原子性重置 Redis 计数为 0</li>
     *   <li>批量更新数据库：{@code UPDATE gif SET download_count = download_count + ?}</li>
     * </ol>
     */
    private void syncDownloadCountToDatabase() {
        try {
            // 一次Redis请求完成：扫描匹配的key + 过滤非零值 + 获取值 + 重置为0
            Map<String, Long> nonZeroCounters = cacheService.scanAndResetNonZeroCounters(DOWNLOAD_COUNT_KEY + "*");
            
            if (nonZeroCounters.isEmpty()) {
                log.info("没有GIF下载记录需要同步");
                return;
            }
            
            log.info("发现{}个GIF下载记录需要同步", nonZeroCounters.size());
            int prefixLength = DOWNLOAD_COUNT_KEY.length();

            // gifId -> downloadCount
            Map<Long, Long> incrementMap = new HashMap<>();
            
            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
                String countKey = entry.getKey();
                Long downloadCount = entry.getValue();
                
                try {
                    // 提取ID - 使用前缀长度直接获取
                    if (countKey.length() <= prefixLength) {
                        log.warn("downloadCount无效的键格式: {}", countKey);
                        continue;
                    }
                    String gifId = countKey.substring(prefixLength);

                    if (downloadCount != null && downloadCount > 0) {
                        incrementMap.put(Long.parseLong(gifId), downloadCount);
                    }                    
                } catch (Exception e) { 
                    log.error("处理下载键失败: {}, 错误: {}", countKey, e.getMessage());
                }
            }

            if (incrementMap.isEmpty()) {
                return;
            }

            // 统一处理
            int updatedCount = gifMapper.updateDownloadCountBatchByMap(incrementMap);

            log.info("已批量更新{}个GIF的downloadCount, 计划更新{}个, 相差{}个", updatedCount, incrementMap.size(), incrementMap.size() - updatedCount);
        } catch (Exception e) {
            log.error("同步下载次数失败: {}", e.getMessage(), e);
        }
    }


    /**
     * 同步 GIF 查看次数到数据库
     *
     * <p>从 Redis 扫描所有查看计数键，提取非零值后批量更新数据库，并原子性重置 Redis 计数。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>扫描 Redis 键：{@code gif:view:*}</li>
     *   <li>过滤非零值并获取计数</li>
     *   <li>原子性重置 Redis 计数为 0</li>
     *   <li>批量更新数据库：{@code UPDATE gif SET view_count = view_count + ?}</li>
     * </ol>
     */
    private void syncViewCountsToDatabase() {
        try {
            // 一次Redis请求完成：扫描匹配的key + 过滤非零值 + 获取值 + 重置为0
            Map<String, Long> nonZeroCounters = cacheService.scanAndResetNonZeroCounters(VIEW_COUNT_KEY + "*");

            if (nonZeroCounters.isEmpty()) {
                log.info("没有GIF查看记录需要同步");
                return;
            }

            log.info("发现{}个GIF查看记录需要同步", nonZeroCounters.size());
            int prefixLength = VIEW_COUNT_KEY.length();

            // gifId -> viewCount
            Map<Long, Long> incrementMap = new HashMap<>();
            
            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
                String countKey = entry.getKey();
                Long viewCount = entry.getValue();

                try {
                    if (countKey.length() <= prefixLength) {
                        log.warn("viewCount无效的键格式: {}", countKey);
                        continue;
                    }
                    String gifId = countKey.substring(prefixLength);

                    if (viewCount != null && viewCount != 0) {
                        incrementMap.put(Long.parseLong(gifId), viewCount);
                    }
                } catch (Exception e) {
                    log.error("处理查看键失败: {}, 错误: {}", countKey, e.getMessage());
                }
            }

            if (incrementMap.isEmpty()) {
                return;
            }

            // 统一处理
            int updatedCount = gifMapper.updateViewCountBatchByMap(incrementMap);

            log.info("已批量更新{}个GIF的viewCount, 计划更新{}个, 相差{}个", updatedCount, incrementMap.size(), incrementMap.size() - updatedCount);
        } catch (Exception e) {
            log.error("同步GIF查看数数据失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 同步 GIF 点赞次数到数据库
     *
     * <p>从 Redis 扫描所有点赞计数键，提取非零值后批量更新数据库，并原子性重置 Redis 计数。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>扫描 Redis 键：{@code gif:like:*}</li>
     *   <li>过滤非零值并获取计数</li>
     *   <li>原子性重置 Redis 计数为 0</li>
     *   <li>批量更新数据库：{@code UPDATE gif SET like_count = like_count + ?}</li>
     * </ol>
     *
     * <p><b>注意：</b>由于重置为 0 到 DB 更新这段时间查询结果会不一致，
     * 所以直接在定时任务中更新数据库，不使用 SQS 异步处理。</p>
     */
    private void syncLikeCountsToDatabase() {
        try {
            // 一次Redis请求完成：扫描匹配的key + 过滤非零值 + 获取值 + 重置为0
            Map<String, Long> nonZeroCounters = cacheService.scanAndResetNonZeroCounters(LIKE_COUNT_KEY + "*");
            
            if (nonZeroCounters.isEmpty()) {
                log.info("没有GIF点赞和取消点赞记录需要同步");
                return;
            }
            
            log.info("发现{}个GIF点赞和取消点赞记录需要同步", nonZeroCounters.size());
            int prefixLength = LIKE_COUNT_KEY.length();

            // gifId -> likeCount
            Map<Long, Long> incrementMap = new HashMap<>();

            // 先串行处理如果耗时过长考虑并发处理 -- ForkJoinPool + ConcurrentLinkedDeque
            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
                String countKey = entry.getKey();
                Long likeCount = entry.getValue();
                
                try {
                    // 提取ID - 使用前缀长度直接获取
                    if (countKey.length() <= prefixLength) {
                        log.warn("likeCount无效的键格式: {}", countKey);
                        continue;
                    }
                    String gifId = countKey.substring(prefixLength);
                    
                    if (likeCount != null && likeCount != 0) {
                        incrementMap.put(Long.parseLong(gifId), likeCount);
                    }
                } catch (Exception e) {
                    log.error("处理点赞键失败: {}, 错误: {}", countKey, e.getMessage());
                }
            }

            if (incrementMap.isEmpty()) {
                return;
            }

            // 统一处理
            int updatedCount = gifMapper.updateLikeCountBatchByMap(incrementMap);

            log.info("已批量更新{}个GIF点赞数, 计划更新{}个, 相差{}个", updatedCount, incrementMap.size(), incrementMap.size() - updatedCount);
        } catch (Exception e) {
            log.error("同步GIF点赞数据失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 同步用户点赞记录到数据库（SQS 异步版本 - 生产者）
     *
     * <p>从 Redis 获取所有有点赞/取消点赞数据的用户 ID，
     * 使用虚拟线程并发获取每个用户的数据，封装为消息发送到 SQS 队列，
     * 由 {@link UserLikesConsumer} 异步消费并同步到数据库。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>扫描 Redis 获取有数据的用户 ID 列表</li>
     *   <li>使用虚拟线程并发获取每个用户的点赞数据</li>
     *   <li>封装为 {@link UserLikesMessage} 发送到 SQS</li>
     *   <li>消费者异步处理数据库同步（新增/删除点赞记录）</li>
     * </ol>
     *
     * <p><b>为什么使用 SQS：</b>解耦数据获取和数据库写入，避免阻塞定时任务</p>
     *
     * @see UserLikesConsumer
     * @see UserLikesMessage
     * @see #fetchSingleUserData(String)
     */
    private void syncUserLikesToDatabaseConcurrent() {
        try {
            // 获取所有有like/dislike数据的用户ID
            Set<String> userIds = cacheService.getUserIdsWithLikeDataOptimized(
                    USER_LIKE_CATEGORY_KEY,
                    USER_DISLIKE_KEY,
                    SCAN_COUNT);

            if (userIds.isEmpty()) {
                log.info("没有有效的用户喜欢记录需要同步");
                return;
            }
            log.info("发现{}个用户的喜欢和不喜欢记录需要同步，使用sqs处理当前是生产者", userIds.size());

            // 直接forEach或者直增强for都可以
            userIds.forEach(userId ->
                    CompletableFuture.runAsync(() -> {
                        try {
                            // 添加 30 秒超时,避免无限等待导致虚拟线程阻塞
                            if (!dataFetchSemaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                                log.error("syncUserLikes获取数据信号量超时(30s),用户ID: {}", userId);
                                return;
                            }
                            try {
                                UserLikeData data = fetchSingleUserData(userId);
                                if (data != null) {
                                    // send to SQS
                                    UserLikesMessage userLikesMessage = UserLikesMessage.builder()
                                            .userId(data.userId)
                                            .deleteLikes(data.deleteLikes)
                                            .newLikes(data.newLikes)
                                            .build();
                                    messageService.send(JSONUtil.toJsonStr(userLikesMessage), SQS_QUEUE_URL, MessageType.USER_LIKES_MESSAGE);
                                }
                            } finally {
                                // 释放信号量许可
                                dataFetchSemaphore.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("syncUserLikes等待信号量许可时被中断，用户ID: {}", userId);
                        } catch (Exception e) {
                            log.error("获取用户{}数据失败: {}", userId, e.getMessage(), e);
                        }
                    }, virtualDataFetchExecutor)); // 虚拟线程并发

            log.info("已发送{}个请求，等待消费者处理完剩余数据", userIds.size());

        } catch (Exception e) {
            log.error("生产者同步用户喜欢记录失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 更新热门标签缓存并清理无用标签
     *
     * <p>每小时执行一次，完成两个任务：</p>
     * <ol>
     *   <li>从数据库查询热门标签，更新 Redis 缓存</li>
     *   <li>删除使用次数为 0 的标签（每次最多 200 条，避免大量回表）</li>
     * </ol>
     *
     * <p><b>注意：</b>删除 GIF 时会删除 gif_tag 关联，这里只删除 tag 表记录</p>
     */
    @Scheduled(fixedRate = 60 * 1000 * 60)
    public void replaceHotTagsAndClearZeroTag() {
        try {
            List<Tag> hotTags = tagMapper.selectHotTags();
            String[] args = new String[hotTags.size() * 2];
            for (int i = 0; i < hotTags.size(); i++) {
                Tag tag = hotTags.get(i);
                args[i * 2] = String.valueOf(tag.getUseCount());
                args[i * 2 + 1] = tag.getName();
            }
            cacheService.replaceHotTags(args, HOT_TAG_KEY);

            // 删除使用次数为0的Tag数据 -- 删除gif时会删除gifTag，这里删除Tag先不删除gifTag
            LambdaQueryWrapper<Tag> queryWrapper = new LambdaQueryWrapper<>();
            tagMapper.delete(queryWrapper.eq(Tag::getUseCount, 0).last("limit 200")); // limit 防止大量数据回表
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 清理已删除的 GIF 文件（事件监听）
     *
     * <p>监听 {@link GifDeleteEvent} 事件，批量删除 R2 存储上的垃圾文件，
     * 并清理数据库中的删除记录。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>从 gif_delete 表获取待删除记录</li>
     *   <li>批量删除 R2 存储上的文件（使用 S3 批量删除 API）</li>
     *   <li>更新 tag 表的使用次数，删除 gif_tag 关联</li>
     *   <li>删除成功的记录从 gif_delete 表移除</li>
     *   <li>删除失败的记录保存到 gif_delete_failed 表，等待人工处理</li>
     * </ol>
     *
     * <p><b>注意：</b>不加 try-catch，让事务回滚机制生效</p>
     *
     * @param event GIF 删除事件，包含删除数量和批次大小
     */
    @Transactional(rollbackFor = Exception.class)
    @EventListener
    public void clearDeletedGifs(GifDeleteEvent event) {
        log.info("开始清理删除记录表中的数据和R2垃圾文件...总删除数量为:{}", event.getDelCount());

        // 调用服务层方法获取需要删除的记录（不会从数据库中删除）
        List<GifDelete> deleteRecords = gifDeleteService.getDeleteRecords(event.getBatchSize());

        if (deleteRecords.isEmpty()) {
            log.info("没有需要清理的删除记录");
            return;
        }

        log.info("获取到{}条需要从R2删除的文件记录", deleteRecords.size());

        // 获取S3Client
        S3Client s3Client = r2FileUtils.getS3Client();

        // 准备批量删除对象
        List<ObjectIdentifier> objectsToDelete = new ArrayList<>();
        List<String> gifTagsToDelete = new ArrayList<>();
        int failUrls = 0;

        // 收集要删除的对象标识符
        for (GifDelete gifDelete : deleteRecords) {
            String fileUrl = gifDelete.getFileUrl();
            String objectKey = extractObjectKeyFromUrl(fileUrl);

            if (objectKey != null) {
                objectsToDelete.add(
                    ObjectIdentifier.builder()
                        .key(objectKey)
                        .build()
                );
            } else {
                failUrls++;
                log.error("无法从URL提取对象键: {}", fileUrl);
            }

            // 收集要删除的gifTag关联
            if (gifDelete.getFileId() != null) gifTagsToDelete.add(gifDelete.getFileId());
        }

        if (objectsToDelete.isEmpty()) {
            log.info("没有有效的对象需要删除");
            return;
        }

        // 执行批量删除
        DeleteObjectsResponse deleteResponse = s3Client.deleteObjects(
                // 创建批量删除请求
                DeleteObjectsRequest.builder()
                        .bucket(R2FileUtils.BUCKET_NAME)
                        .delete(
                                Delete.builder()
                                        .objects(objectsToDelete)
                                        .quiet(true) // 安静模式，只返回删除失败的对象
                                        .build()
                        )
                        .build()
        );

        // 处理删除结果
        Set<String> failedKeys = new HashSet<>();

        // 如果有错误，收集失败的key
        if (deleteResponse.hasErrors() && !deleteResponse.errors().isEmpty()) {
            deleteResponse.errors().forEach(
                    error -> {
                        failedKeys.add("https://mynnmy.top/" + error.key());
                        log.error("删除对象失败: 键={}, 错误码={}, 消息={}",
                                error.key(), error.code(), error.message());
                    });
        }

        // 更新tag表和gifTag表
        if (gifTagsToDelete.isEmpty()) {
            log.info("没有有效的gifTag需要删除");
        } else {
            List<GifTag> gifTags = gifTagService.list(new LambdaQueryWrapper<GifTag>().in(GifTag::getGifId, gifTagsToDelete));
            if (!gifTags.isEmpty()) {
                Map<Long, Long> tagIdCountMap = gifTags.stream().collect(Collectors.groupingBy(GifTag::getTagId, Collectors.counting()));
                // 删除gifTag关联
                gifTagService.remove(new LambdaQueryWrapper<GifTag>().in(GifTag::getGifId, gifTagsToDelete));
                // 更新tag表
                tagMapper.updateUseCountByMap(tagIdCountMap);
            }
        }

        // 记录删除结果
        log.info("成功从R2批量删除了{}个文件，失败{}个", deleteRecords.size() - failedKeys.size(), failedKeys.size());
        // 记录无法解析URL的情况
        if (failUrls > 0) {
            log.warn("有{}个URL无法解析为对象键", failUrls);
        }

        List<Long> successIds = deleteRecords.stream()
                .filter(record -> !failedKeys.contains(record.getFileUrl()))
                .map(GifDelete::getId).toList();

        // 只有在成功删除了R2文件后，才从数据库中删除记录
        if (!successIds.isEmpty()) {
            boolean dbDeleteSuccess = gifDeleteService.removeDeleteRecords(successIds);
            if (dbDeleteSuccess) {
                log.info("成功从数据库中删除了{}条记录", successIds.size());
            } else {
                log.error("从数据库中删除记录失败");
            }
        }

        // 将failedKeys记录存到新表 --- 后续人工排查
        if (!failedKeys.isEmpty()) {
            gifDeleteFailedService.saveBatch(
                failedKeys.stream()
                        .map(key -> new GifDeleteFailed()
                                .setFileUrl("https://mynnmy.top/" + key)
                                .setCreatedAt(LocalDateTime.now()
                                )
                        ).toList()
            );
        }
    }
    
    /**
     * 从 URL 中提取 S3 对象键
     *
     * <p>从完整的 CDN URL 中提取对象存储的键名。</p>
     *
     * <p><b>示例：</b></p>
     * <pre>
     * 输入：<a href="">https://mynnmy.top/gifs/01/123/abc.gif</a>
     * 输出：gifs/01/123/abc.gif
     * </pre>
     *
     * <p><b>性能优化：</b>使用固定长度截取（19 字符），
     * 比 split 方式快 10 倍（10000 次：0.5ms vs 6ms）</p>
     *
     * @param url 文件 URL
     * @return S3 对象键，如果 URL 为空则返回 null
     */
    private String extractObjectKeyFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        // 使用固定前缀截取，效率更高 10000次 0.5ms 而 split再取parts[3] 6ms
        // 这里写死固定长度截取 -- https://mynnmy.top/
        return url.substring(19);
    }

    /**
     * 用户喜欢数据传输对象
     */
    public record UserLikeData(
            String userId,
            List<UserLike> newLikes,
            List<UserLike> deleteLikes) {

    }

    /**
     * 获取单个用户的点赞数据
     *
     * <p>从 Redis 获取用户的点赞和取消点赞记录，并自动清理 Redis 数据。
     * 纯 IO 操作，适合虚拟线程执行。</p>
     *
     * <p><b>数据来源：</b></p>
     * <ul>
     *   <li>{@code user:like:category:{userId}} - Hash 结构，存储点赞的 GIF 和分类</li>
     *   <li>{@code user:dislike:{userId}} - Set 结构，存储取消点赞的 GIF</li>
     * </ul>
     *
     * @param userId 用户 ID
     * @return 用户点赞数据对象，如果没有数据则返回 null
     */
    private UserLikeData fetchSingleUserData(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            String likeHashKey = USER_LIKE_CATEGORY_KEY + userId;
            String dislikeSetKey = USER_DISLIKE_KEY + userId;

            Map<String, Long> likedGifMapping = cacheService.getLikeCategoryIdsSafely(likeHashKey, true);
            Set<String> dislikeGifIds = cacheService.getStringSetSafely(dislikeSetKey, true);

            // 没有点赞和取消点赞记录，直接返回null
            if (CollectionUtil.isEmpty(likedGifMapping) && CollectionUtil.isEmpty(dislikeGifIds)) {
                return null;
            }

            List<UserLike> newLikes = new ArrayList<>();
            List<UserLike> deleteLikes = new ArrayList<>();

            for (Map.Entry<String, Long> entry : likedGifMapping.entrySet()) {
                try {
                    String gifId = entry.getKey();
                    Long categoryId = entry.getValue();

                    UserLike userLike = new UserLike()
                        .setUserId(userIdLong)
                        .setGifId(Long.parseLong(gifId))
                        .setUserLikeCategoryId(categoryId);

                    newLikes.add(userLike);
                } catch (Exception e) {
                    log.error("处理用户{}的点赞记录失败: gifId={}", userId, entry.getKey(), e);
                }
            }

            for (String gifId : dislikeGifIds) {
                try {
                    UserLike userLike = new UserLike()
                            .setUserId(userIdLong)
                            .setGifId(Long.parseLong(gifId));

                    deleteLikes.add(userLike);
                } catch (Exception e) {
                    log.error("处理用户{}的取消点赞记录失败: gifId={}", userId, gifId, e);
                }
            }

            return new UserLikeData(userId, newLikes, deleteLikes);

        } catch (Exception e) {
            log.error("获取用户{}数据失败: {}", userId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 定时清理软删除的评论（物理删除）
     *
     * <p>每天凌晨 3 点执行，使用多表 DELETE 一次性删除评论及其点赞记录。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>使用 LEFT JOIN 同时删除 comment 和 comment_like 表的数据</li>
     *   <li>WHERE 条件：status=0 且 updated_at &lt; NOW() - 1天</li>
     * </ol>
     *
     * <p><b>设计理念：</b>软删除 + 定时物理删除，24小时缓冲期避免误删</p>
     * <p><b>适用场景：</b>包括用户删除的评论和因GIF被删除而软删除的评论</p>
     * <p><b>返回值：</b>删除的总行数（comment + comment_like）</p>
     */
    // @Scheduled(cron = "0 0 3 * * ?")
    @Scheduled(fixedRate = SYNC_INTERVAL * 60 * 1000) // 测试用
    public void cleanupDeletedComments() {
        try {
            // LocalDateTime expireTime = LocalDateTime.now().minusDays(1);
            // log.info("开始清理软删除评论（24小时前）...");

            LocalDateTime expireTime = LocalDateTime.now();
            log.info("开始清理软删除评论（当前）...");

            // 1. 清理软删除评论及其点赞记录
            int deletedCount = commentMapper.deleteCommentsAndCommentLikes(expireTime);
            if (deletedCount == 0) {
                log.info("没有需要清理的过期评论");
            } else {
                log.info("定时清理完成：共物理删除 {} 条过期评论及其点赞记录", deletedCount);
            }

            // 2. 清理孤儿点赞记录（点赞后评论被删除的情况）
            int orphanCount = commentMapper.deleteOrphanCommentLikes();
            if (orphanCount > 0) {
                log.info("清理孤儿点赞记录完成：共删除 {} 条", orphanCount);
            }
        } catch (Exception e) {
            log.error("清理软删除评论失败", e);
        }
    }
    
    /**
     * 同步评论点赞次数到数据库
     *
     * <p>从 Redis 扫描所有评论点赞计数键，提取非零值后批量更新数据库，并原子性重置 Redis 计数。</p>
     * <p>同时使用 HINCRBY 原子更新评论详情缓存（Hash 结构）中的 likeCount 字段</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>扫描 Redis 键：{@code comment:like:*}</li>
     *   <li>过滤非零值并获取计数</li>
     *   <li>原子性重置 Redis 计数为 0</li>
     *   <li>批量更新数据库：{@code UPDATE comment SET like_count = like_count + ?}</li>
     *   <li>使用 HINCRBY 原子更新 Hash 缓存：{@code comment:detail:commentId.likeCount}</li>
     * </ol>
     */
    private void syncCommentLikeCountsToDatabase() {
        try {
            Map<String, Long> nonZeroCounters = cacheService.scanAndResetNonZeroCounters(COMMENT_LIKE_COUNT_KEY + "*");
            
            if (nonZeroCounters.isEmpty()) {
                log.info("没有评论点赞记录需要同步");
                return;
            }
            
            log.info("发现{}个评论点赞记录需要同步", nonZeroCounters.size());
            int prefixLength = COMMENT_LIKE_COUNT_KEY.length();
            
            // commentId -> likeCount
            Map<Long, Long> incrementMap = new HashMap<>();
            
            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
                String countKey = entry.getKey();
                Long likeCount = entry.getValue();
                
                try {
                    // 提取ID - 使用前缀长度直接获取
                    if (countKey.length() <= prefixLength) {
                        log.warn("commentLike无效的键格式: {}", countKey);
                        continue;
                    }
                    String commentId = countKey.substring(prefixLength);
                    
                    if (likeCount != null && likeCount != 0) {
                        incrementMap.put(Long.parseLong(commentId), likeCount);
                    }
                } catch (Exception e) {
                    log.error("处理评论点赞键失败: {}, 错误: {}", countKey, e.getMessage());
                }
            }
            
            if (incrementMap.isEmpty()) {
                return;
            }
            
            // 1. 先更新数据库（Source of Truth）
            int updatedCount = commentMapper.updateLikeCountBatchByMap(incrementMap);
            log.info("DB更新成功：{}条评论点赞数已同步", updatedCount);
            
            // 2. DB更新成功后，使用 HINCRBY 原子更新 Hash 缓存中的 likeCount
            int cacheUpdatedCount = 0;
            int cacheDeletedCount = 0;
            
            for (Map.Entry<Long, Long> entry : incrementMap.entrySet()) {
                Long commentId = entry.getKey();
                Long increment = entry.getValue();
                String hashKey = COMMENT_DETAIL_KEY + commentId;
                
                try {
                    // 使用 HINCRBY 原子递增 likeCount 字段（如果 Hash 存在）
                    if (cacheService.hasKey(hashKey)) {
                        cacheService.hashIncrementWithExpire(hashKey, "likeCount", increment, 
                                                            COMMENT_DETAIL_CACHE_TTL, TimeUnit.MINUTES);
                        cacheUpdatedCount++;
                    }
                    // 如果缓存不存在，跳过（下次查询会从 DB 重建，数据一致）
                } catch (Exception e) {
                    log.warn("更新缓存 likeCount 失败，删除该缓存: commentId={}, error={}", 
                            commentId, e.getMessage());
                    // 更新失败，删除缓存以保证一致性
                    try {
                        cacheService.delete(hashKey);
                        cacheDeletedCount++;
                    } catch (Exception deleteEx) {
                        log.warn("删除缓存失败，忽略: commentId={}", commentId);
                        // 删除失败也无所谓，缓存会在 TTL 后过期
                    }
                }
            }
            
            log.info("同步完成：DB已更新{}条，缓存已更新{}个，缓存已删除{}个", 
                    updatedCount, cacheUpdatedCount, cacheDeletedCount);
            
        } catch (Exception e) {
            log.error("同步评论点赞数失败", e);
        }
    }

    /**
     * 同步用户评论点赞记录到数据库（SQS 异步版本 - 生产者）
     *
     * <p>从 Redis 获取所有有评论点赞/取消点赞数据的用户 ID，
     * 使用虚拟线程并发获取每个用户的数据，封装为消息发送到 SQS 队列，
     * 由 {@link CommentLikesConsumer} 异步消费并同步到数据库。</p>
     *
     * <p><b>处理流程：</b></p>
     * <ol>
     *   <li>扫描 Redis 获取有数据的用户 ID 列表</li>
     *   <li>使用虚拟线程并发获取每个用户的评论点赞数据</li>
     *   <li>封装为 {@link CommentLikesMessage} 发送到 SQS</li>
     *   <li>消费者异步处理数据库同步（新增/删除评论点赞记录）</li>
     * </ol>
     *
     * <p><b>为什么使用 SQS：</b>解耦数据获取和数据库写入，避免阻塞定时任务</p>
     *
     * @see CommentLikesConsumer
     * @see CommentLikesMessage
     * @see #fetchSingleUserCommentData(String)
     */
    private void syncUserCommentLikesToDatabase() {
        try {
            // 获取所有有评论点赞/取消点赞数据的用户ID（类似GIF的逻辑）
            Set<String> userIds = cacheService.getUserIdsWithLikeDataOptimized(
                    USER_COMMENT_LIKE_KEY,
                    USER_COMMENT_DISLIKE_KEY,
                    SCAN_COUNT
            );
            
            if (userIds == null || userIds.isEmpty()) {
                log.info("没有用户评论点赞记录需要同步");
                return;
            }
            
            log.info("发现{}个用户的评论点赞/取消点赞记录需要同步，使用SQS处理（当前是生产者）", userIds.size());
            
            // 使用虚拟线程并发处理每个用户
            userIds.forEach(userId ->
                CompletableFuture.runAsync(() -> {
                    try {
                        // 添加 30 秒超时,避免无限等待导致虚拟线程阻塞
                        if (!dataFetchSemaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                            log.error("syncUserCommentLikes获取数据信号量超时(30s),用户ID: {}", userId);
                            return;
                        }
                        try {
                            CommentLikesMessage message = fetchSingleUserCommentData(userId);
                            if (message != null) {
                                // 发送到SQS
                                messageService.send(
                                    JSONUtil.toJsonStr(message),
                                    SQS_QUEUE_URL,
                                    MessageType.COMMENT_LIKES_MESSAGE
                                );
                                int newCount = message.getNewLikes() != null ? message.getNewLikes().size() : 0;
                                int deleteCount = message.getDeleteLikes() != null ? message.getDeleteLikes().size() : 0;
                                log.info("已发送用户{}的评论点赞数据到SQS，新增{}条，删除{}条",
                                        message.getUserId(), newCount, deleteCount);
                            }
                        } finally {
                            // 释放信号量许可
                            dataFetchSemaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("syncUserCommentLikes等待信号量许可时被中断，用户ID: {}", userId);
                    } catch (Exception e) {
                        log.error("处理用户{}评论点赞数据失败: 错误: {}",
                                userId, e.getMessage(), e);
                    }
                }, virtualDataFetchExecutor)); // 使用虚拟线程池
            
            log.info("已发送{}个评论点赞同步请求到SQS，等待消费者处理", userIds.size());
            
        } catch (Exception e) {
            log.error("生产者同步用户评论点赞记录失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取单个用户的评论点赞数据
     *
     * <p>从 Redis 获取用户的评论点赞和取消点赞记录，并自动清理 Redis 数据。
     * 纯 IO 操作，适合虚拟线程执行。</p>
     *
     * <p><b>数据来源：</b></p>
     * <ul>
     *   <li>{@code user:comment:like:{userId}} - Set 结构，存储点赞的评论 ID</li>
     *   <li>{@code user:comment:dislike:{userId}} - Set 结构，存储取消点赞的评论 ID</li>
     * </ul>
     *
     * @param userId 用户 ID
     * @return 评论点赞消息对象，如果没有数据则返回 null
     */
    private CommentLikesMessage fetchSingleUserCommentData(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            String likeSetKey = USER_COMMENT_LIKE_KEY + userId;
            String dislikeSetKey = USER_COMMENT_DISLIKE_KEY + userId;
            
            // 获取并删除Redis数据（参数true表示删除）
            Set<String> likeCommentIds = cacheService.getStringSetSafely(likeSetKey, true);
            Set<String> dislikeCommentIds = cacheService.getStringSetSafely(dislikeSetKey, true);
            
            // 没有点赞和取消点赞记录，直接返回null
            if (CollectionUtil.isEmpty(likeCommentIds) && CollectionUtil.isEmpty(dislikeCommentIds)) {
                return null;
            }
            
            List<CommentLike> newLikes = new ArrayList<>();
            List<CommentLike> deleteLikes = new ArrayList<>();
            
            // 处理新增点赞
            for (String commentIdStr : likeCommentIds) {
                try {
                    CommentLike commentLike = new CommentLike()
                            .setUserId(userIdLong)
                            .setCommentId(Long.parseLong(commentIdStr));
                    newLikes.add(commentLike);
                } catch (NumberFormatException e) {
                    log.warn("忽略点赞无效的评论ID: {}", commentIdStr);
                }
            }
            
            // 处理取消点赞
            for (String commentIdStr : dislikeCommentIds) {
                try {
                    CommentLike commentLike = new CommentLike()
                            .setUserId(userIdLong)
                            .setCommentId(Long.parseLong(commentIdStr));
                    deleteLikes.add(commentLike);
                } catch (NumberFormatException e) {
                    log.warn("忽略取消点赞无效的评论ID: {}", commentIdStr);
                }
            }
            
            // 如果两个列表都为空，返回null
            if (newLikes.isEmpty() && deleteLikes.isEmpty()) {
                return null;
            }
            
            // 构建消息对象
            return CommentLikesMessage.builder()
                    .userId(userIdLong)
                    .newLikes(newLikes.isEmpty() ? null : newLikes)
                    .deleteLikes(deleteLikes.isEmpty() ? null : deleteLikes)
                    .build();
                    
        } catch (Exception e) {
            log.error("获取用户{}评论点赞数据失败: 错误: {}", 
                    userId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 应用关闭时清理资源
     */
    @PreDestroy
    public void cleanup() {
        log.info("GifScheduleExecutor 清理完成");
    }
}
