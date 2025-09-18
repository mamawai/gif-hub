package com.mawai.ghgif.schedule;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghgif.amazonSQS.consumer.UserLikesConsumer;
import com.mawai.ghgif.amazonSQS.message.UserLikesMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.event.GifDeleteEvent;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghgif.util.R2FileUtils;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.GifDelete;
import com.mawai.ghmbplus.model.GifDeleteFailed;
import com.mawai.ghmbplus.model.UserLike;
import com.mawai.ghmbplus.service.GifDeleteFailedService;
import com.mawai.ghmbplus.service.GifDeleteService;
import com.mawai.ghmbplus.service.GifService;
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

/**
 * Gif定时任务执行器
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class GifScheduleExecutorWithSQS {

    private final CacheService cacheService;
    private final GifService gifService;
    private final GifDeleteService gifDeleteService;
    private final GifDeleteFailedService gifDeleteFailedService;
    private final MessageService messageService;

    // 注入线程池
    private final Executor scheduledExecutor;
    // 虚拟线程执行器，用于数据获取（IO密集型）
    private final Executor virtualDataFetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final R2FileUtils r2FileUtils;

    private static final int SYNC_INTERVAL = 1; // 同步间隔
    private static final int BATCH_SIZE = 100; // 批量处理大小
    private static final int MAX_CONCURRENT_DATA_FETCH = 20; // 最大并发数据获取线程数

    private static final String DOWNLOAD_COUNT_KEY = "gif:download:";
    private static final String LIKE_COUNT_KEY = "gif:like:";
    private static final String VIEW_COUNT_KEY = "gif:view:";
    private static final String USER_LIKE_CATEGORY_KEY = "user:like:category:";
    private static final String USER_DISLIKE_KEY = "user:dislike:";

    @Value("${aws.sqs.base-queue-url}")
    private String SQS_QUEUE_URL;

    /**
     * 定时任务：将Redis中GIF点赞次数和用户喜欢的GIF同步到数据库
     * 
     * <p>该方法按照固定时间间隔（{@link #SYNC_INTERVAL}分钟）执行，
     * 将Redis缓存中的点赞数据持久化到数据库中。主要包含两部分：</p>
     * 
     * <ul>
     *   <li>同步GIF下载次数到数据库（{@link #syncDownloadCountToDatabase()}）</li>
     *   <li>同步GIF点赞计数到数据库（{@link #syncLikeCountsToDatabase()}）</li>
     *   <li>同步用户喜欢记录到数据库（{@link #syncUserLikesToDatabaseConcurrent()}）</li>
     * </ul>
     * 
     * <p>任务执行过程记录完整日志，包括开始、完成和异常信息。</p>
     * 
     * @see #syncLikeCountsToDatabase() 点赞数据同步实现
     * @see #syncUserLikesToDatabaseConcurrent() () 用户喜欢记录同步实现
     * @see CacheService#getKeysWithPattern(String) 获取符合模式的Redis键
     */
    @Scheduled(fixedRate = SYNC_INTERVAL * 60 * 1000) // 转换为毫秒
    public void syncGifLikeCount() {
        log.info("开始同步Redis中的GIF点赞数据到数据库...");
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
        
        // 无需等待所有任务完成，直接返回
        log.info("已启动GIF数据同步任务，将在{}分钟后再次触发同步", SYNC_INTERVAL);
    }

    /**     
     * 同步下载次数到数据库
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

            // 批量处理，每批最多100条记录
            List<Gif> gifsToUpdate = new ArrayList<>();
            
            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
                String countKey = entry.getKey();
                Long downloadCount = entry.getValue();
                
                try {
                    // 提取ID - 使用前缀长度直接获取
                    if (countKey.length() <= prefixLength) {
                        log.warn("无效的键格式: {}", countKey);
                        continue;
                    }
                    String gifId = countKey.substring(prefixLength);

                    if (downloadCount != null && downloadCount > 0) {
                        // 查询GIF记录
                        Gif gif = gifService.getById(gifId);
                        if (gif != null) {
                            // 增加下载次数（增量更新）
                            int newDownloadCount = gif.getDownloadCount() + downloadCount.intValue();
                            gif.setDownloadCount(newDownloadCount);
                            gif.setUpdatedAt(LocalDateTime.now());
                            gifsToUpdate.add(gif);
                            log.debug("准备更新GIF(ID:{})下载数增量: +{}, 新总数: {}", gifId, downloadCount, newDownloadCount);
                            
                            // 达到批量大小时更新数据库
                            if (gifsToUpdate.size() >= BATCH_SIZE) {
                                gifService.updateBatchById(gifsToUpdate);
                                log.info("已批量更新{}个GIF下载数", gifsToUpdate.size());
                                gifsToUpdate.clear();
                            }
                        }
                    }
                    
                } catch (Exception e) { 
                    log.error("处理下载键失败: {}, 错误: {}", countKey, e.getMessage());
                }
            }

            // 处理剩余记录
            if (!gifsToUpdate.isEmpty()) {
                gifService.updateBatchById(gifsToUpdate);
                log.info("已批量更新剩余的{}个GIF下载数", gifsToUpdate.size());
            }
        } catch (Exception e) {
            log.error("同步下载次数失败: {}", e.getMessage(), e);
        }
    }


    /**
     * 同步查看次数到数据库
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

            // 批量处理，每批最多100条记录
            List<Gif> gifsToUpdate = new ArrayList<>();

            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
                String countKey = entry.getKey();
                Long viewCount = entry.getValue();

                try {
                    // 提取ID - 使用前缀长度直接获取
                    if (countKey.length() <= prefixLength) {
                        log.info("无效的键格式: {}", countKey);
                        continue;
                    }
                    String gifId = countKey.substring(prefixLength);

                    if (viewCount != null && viewCount != 0) {
                        // 查询GIF记录
                        Gif gif = gifService.getById(gifId);
                        if (gif != null) {
                            // 增加查看数（增量更新）
                            int newViewCount = gif.getViewCount() + viewCount.intValue();
                            gif.setViewCount(newViewCount);
                            gif.setUpdatedAt(LocalDateTime.now());
                            gifsToUpdate.add(gif);
                            log.info("准备更新GIF(ID:{})查看数增量: {}, 新总数: {}", gifId, viewCount, newViewCount);

                            // 达到批量大小时更新数据库
                            if (gifsToUpdate.size() >= BATCH_SIZE) {
                                gifService.updateBatchById(gifsToUpdate);
                                log.info("已批量更新{}个GIF查看数", gifsToUpdate.size());
                                gifsToUpdate.clear();
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("处理查看键失败: {}, 错误: {}", countKey, e.getMessage());
                }
            }

            // 处理剩余记录
            if (!gifsToUpdate.isEmpty()) {
                gifService.updateBatchById(gifsToUpdate);
                log.info("已批量更新剩余的{}个GIF查看数", gifsToUpdate.size());
            }
        } catch (Exception e) {
            log.error("同步GIF查看数数据失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 同步点赞数据到数据库
     *
     * <p>从Redis获取所有需要同步的GIF点赞计数数据，并将其增量更新到数据库中。
     * 处理流程包括：</p>
     * 
     * <ol>
     *   <li>扫描匹配的key + 过滤非零值 + 获取值 + 重置为0</li>
     *   <li>提取每个键中的GIF ID</li>
     *   <li>获取点赞计数并原子性地重置Redis计数</li>
     *   <li>查询对应GIF记录并增加点赞数（增量更新）</li>
     *   <li>批量更新数据库（每{@link #BATCH_SIZE}条记录一批）</li>
     * </ol>
     * 
     * <p>整个过程有完整的日志记录，包括同步数量和异常处理。</p>
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
            
            // 批量处理，每批最多100条记录
            List<Gif> gifsToUpdate = new ArrayList<>();
            
            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
                String countKey = entry.getKey();
                Long likeCount = entry.getValue();
                
                try {
                    // 提取ID - 使用前缀长度直接获取
                    if (countKey.length() <= prefixLength) {
                        log.info("无效的键格式: {}", countKey);
                        continue;
                    }
                    String gifId = countKey.substring(prefixLength);
                    
                    if (likeCount != null && likeCount != 0) {
                        // 查询GIF记录
                        Gif gif = gifService.getById(gifId);
                        if (gif != null) {
                            // 增加点赞数（增量更新）
                            int newLikeCount = gif.getLikeCount() + likeCount.intValue();
                            gif.setLikeCount(newLikeCount);
                            gif.setUpdatedAt(LocalDateTime.now());
                            gifsToUpdate.add(gif);
                            log.info("准备更新GIF(ID:{})点赞数增量: {}, 新总数: {}", gifId, likeCount, newLikeCount);
                            
                            // 达到批量大小时更新数据库
                            if (gifsToUpdate.size() >= BATCH_SIZE) {
                                gifService.updateBatchById(gifsToUpdate);
                                log.info("已批量更新{}个GIF点赞数", gifsToUpdate.size());
                                gifsToUpdate.clear();
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("处理点赞键失败: {}, 错误: {}", countKey, e.getMessage());
                }
            }
            
            // 处理剩余记录
            if (!gifsToUpdate.isEmpty()) {
                gifService.updateBatchById(gifsToUpdate);
                log.info("已批量更新剩余的{}个GIF点赞数", gifsToUpdate.size());
            }
        } catch (Exception e) {
            log.error("同步GIF点赞数据失败: {}", e.getMessage(), e);
        }
    }

    
    /**
     * 同步用户喜欢记录到数据库 - 异步SQS版本（生产者角色）
     * 
     * <p>该方法已重构为使用Amazon SQS进行消息队列解耦，实现异步处理用户的喜欢/不喜欢数据同步。
     * 作为生产者角色，负责获取Redis中的用户行为数据并发送到SQS队列，由消费者异步处理数据库同步操作。</p>
     * 
     * <h3>处理流程：</h3>
     * <ol>
     *   <li>调用 {@code cacheService.getUserIdsWithLikeDataOptimized()} 获取Redis中有数据的用户ID</li>
     *   <li>如果无数据则直接返回，记录日志</li>
     *   <li>将用户ID列表按 {@value #MAX_CONCURRENT_DATA_FETCH} 分批处理</li>
     *   <li>每批调用 {@code fetchUserDataConcurrently()} 并发获取用户数据</li>
     *   <li>将数据封装为 {@code UserLikesMessage} 发送到SQS队列</li>
     *   <li>由 {@code UserLikesConsumer} 异步消费并同步到数据库</li>
     * </ol>
     * 
     * <h3>相关组件：</h3>
     * <ul>
     *   <li>{@code UserLikesConsumer} - SQS消息消费者，处理数据库同步</li>
     *   <li>{@code UserLikesMessage} - 用户喜欢数据的消息封装类</li>
     *   <li>{@code AmazonSQSService} - SQS服务封装，负责消息发送</li>
     *   <li>{@code CacheService} - Redis缓存服务，提供用户行为数据</li>
     * </ul>
     * 
     * @since 2.0.0 - SQS异步重构版本
     * @see UserLikesConsumer#handleMessage()
     * @see UserLikesMessage
     * @see #fetchUserDataConcurrently(List)
     */
    private void syncUserLikesToDatabaseConcurrent() {
        try {
            // 获取所有有like/dislike数据的用户ID
            Set<String> userIds = cacheService.getUserIdsWithLikeDataOptimized(USER_LIKE_CATEGORY_KEY, USER_DISLIKE_KEY);

            if (userIds.isEmpty()) {
                log.info("没有有效的用户喜欢记录需要同步");
                return;
            }
            log.info("发现{}个用户的喜欢和不喜欢记录需要同步，使用sqs处理当前是生产者", userIds.size());

            List<String> userIdList = new ArrayList<>(userIds);
            // 控制并发数，避免Redis压力过大
            for (int i = 0; i < userIdList.size(); i += MAX_CONCURRENT_DATA_FETCH) {
                fetchUserDataConcurrently(userIdList.subList(i, Math.min(i + MAX_CONCURRENT_DATA_FETCH, userIdList.size())));
            }
            log.info("已发送{}个请求，等待消费者处理完剩余数据", userIdList.size());

        } catch (Exception e) {
            log.error("生产者同步用户喜欢记录失败: {}", e.getMessage(), e);
        }
    }

     /**
     * 监听GifDeleteEvent事件，清理删除记录表中的数据 -- 删除R2层面的垃圾文件
     * 不加try-catch，避免异常回滚失败
     *
     * <p>该方法清理已被标记为删除的GIF文件，流程如下：</p>
     *
     * <ol>
     *   <li>监听GifDeleteEvent事件</li>
     *   <li>从删除记录表中获取需要删除的记录</li>
     *   <li>批量删除对应的R2存储上的实际文件</li>
     *   <li>R2文件删除成功后，再从数据库中删除这些记录</li>
     * </ol>
     *
     * <p>该任务确保系统垃圾文件得到定期清理，释放存储空间</p>
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
        int failUrls = 0;

        // 收集要删除的对象标识符
        for (GifDelete record : deleteRecords) {
            String fileUrl = record.getFileUrl();
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
            deleteResponse.errors().forEach(error -> {
                failedKeys.add("https://mynnmy.top/" + error.key());
                log.error("删除对象失败: 键={}, 错误码={}, 消息={}",
                    error.key(), error.code(), error.message());
            });
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
     * 从URL中提取S3对象键
     * 例如从 <a href="">https://mynnmy.top/gifs/01/123/abc.gif</a> 提取 "gifs/01/123/abc.gif"
     *
     * @param url 文件URL
     * @return 对象键
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
     * 使用虚拟线程并发获取用户数据（带背压控制）
     */
    private void fetchUserDataConcurrently(List<String> userIds) {
        userIds.forEach(userId ->
                CompletableFuture.runAsync(() -> {
                    try {
                        UserLikeData data = fetchSingleUserData(userId);
                        if (data != null) {
                            // send to SQS
                            UserLikesMessage userLikesMessage = UserLikesMessage.builder().userId(data.userId).deleteLikes(data.deleteLikes).newLikes(data.newLikes).build();
                            messageService.send(JSONUtil.toJsonStr(userLikesMessage), SQS_QUEUE_URL, MessageType.USER_LIKES_MESSAGE);
                        }
                    } catch (Exception e) {
                        log.error("获取用户{}数据失败: {}", userId, e.getMessage(), e);
                    }
                }, virtualDataFetchExecutor));
    }

    /**
     * 获取单个用户的数据（纯IO操作，适合虚拟线程）
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
     * 应用关闭时清理资源
     */
    @PreDestroy
    public void cleanup() {
        log.info("GifScheduleExecutor 清理完成");
    }
}
