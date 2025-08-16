package com.mawai.ghgif.schedule;

import cn.hutool.core.collection.CollectionUtil;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghgif.event.GifDeleteEvent;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.GifDelete;
import com.mawai.ghmbplus.model.GifDeleteFailed;
import com.mawai.ghmbplus.model.UserLike;
import com.mawai.ghmbplus.service.GifDeleteFailedService;
import com.mawai.ghmbplus.service.GifDeleteService;
import com.mawai.ghmbplus.service.GifService;
import com.mawai.ghmbplus.service.UserLikeService;
import com.mawai.ghgif.util.R2FileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Gif定时任务执行器
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class GifScheduleExecutor {

    private final CacheService cacheService;
    private final GifService gifService;
    private final GifDeleteService gifDeleteService;
    private final GifDeleteFailedService gifDeleteFailedService;

    // 注入线程池
    private final Executor fileUploadExecutor;
    private final UserLikeService userLikeService;
    private final R2FileUtils r2FileUtils;

    private static final int SYNC_INTERVAL = 1; // 同步间隔
    private static final int BATCH_SIZE = 100; // 批量处理大小
    private static final int USER_BATCH_SIZE = 10;
    private static final String DOWNLOAD_COUNT_KEY = "gif:download:";
    private static final String LIKE_COUNT_KEY = "gif:like:";
    private static final String USER_LIKE_KEY = "user:like:";

    /**
     * 定时任务：将Redis中GIF点赞次数和用户喜欢的GIF同步到数据库
     * 
     * <p>该方法按照固定时间间隔（{@link #SYNC_INTERVAL}分钟）执行，
     * 将Redis缓存中的点赞数据持久化到数据库中。主要包含两部分：</p>
     * 
     * <ul>
     *   <li>同步GIF点赞计数到数据库（{@link #syncLikeCountsToDatabase()}）</li>
     *   <li>同步用户喜欢记录到数据库（{@link #syncUserLikesToDatabase()}）</li>
     * </ul>
     * 
     * <p>任务执行过程记录完整日志，包括开始、完成和异常信息。</p>
     * 
     * @see #syncLikeCountsToDatabase() 点赞数据同步实现
     * @see #syncUserLikesToDatabase() 用户喜欢记录同步实现
     * @see CacheService#getKeysWithPattern(String) 获取符合模式的Redis键
     */
    @Scheduled(fixedRate = SYNC_INTERVAL * 60 * 1000) // 转换为毫秒
    public void syncGifLikeCount() {
        log.info("开始同步Redis中的GIF点赞数据到数据库...");
        // 使用CompletableFuture并发执行，不等待完成
        CompletableFuture
            .runAsync(this::syncDownloadCountToDatabase, fileUploadExecutor)
            .exceptionally(e -> {
                log.error("同步下载计数失败: {}", e.getMessage(), e);
                return null;
            });
            
        CompletableFuture
            .runAsync(this::syncLikeCountsToDatabase, fileUploadExecutor)
            .exceptionally(e -> {
                log.error("同步点赞计数失败: {}", e.getMessage(), e);
                return null;
            });
            
        CompletableFuture
            .runAsync(this::syncUserLikesToDatabase, fileUploadExecutor)
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
            // 从Redis获取所有需要同步的下载次数数据
            Set<String> downloadKeys = cacheService.getKeysWithPattern(DOWNLOAD_COUNT_KEY + "*");
            if (downloadKeys.isEmpty()) {
                log.info("没有GIF下载记录需要同步");
                return;
            }
            
            log.info("发现{}个GIF下载记录需要同步", downloadKeys.size());
            int prefixLength = DOWNLOAD_COUNT_KEY.length();

            // 批量处理，每批最多100条记录
            List<Gif> gifsToUpdate = new ArrayList<>();
            for (String countKey : downloadKeys) {
                try {
                    // 提取ID - 使用前缀长度直接获取
                    if (countKey.length() <= prefixLength) {
                        log.warn("无效的键格式: {}", countKey);
                        continue;
                    }
                    String gifId = countKey.substring(prefixLength);

                    // 获取下载次数
                    Long downloadCount = cacheService.getAndReset(countKey);

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
     * 同步点赞数据到数据库
     *
     * <p>从Redis获取所有需要同步的GIF点赞计数数据，并将其增量更新到数据库中。
     * 处理流程包括：</p>
     * 
     * <ol>
     *   <li>从Redis获取所有符合{@code LIKE_COUNT_KEY + "*"}模式的键</li>
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
            // 直接从Redis获取所有需要同步的点赞数据
            Set<String> likeKeys = cacheService.getKeysWithPattern(LIKE_COUNT_KEY + "*");
            if (likeKeys.isEmpty()) {
                log.info("没有GIF点赞和取消点赞记录需要同步");
                return;
            }
            
            log.info("发现{}个GIF点赞和取消点赞记录需要同步", likeKeys.size());
            int prefixLength = LIKE_COUNT_KEY.length();
            
            // 批量处理，每批最多100条记录
            List<Gif> gifsToUpdate = new ArrayList<>();
            
            for (String countKey : likeKeys) {
                try {
                    // 提取ID - 使用前缀长度直接获取
                    if (countKey.length() <= prefixLength) {
                        log.warn("无效的键格式: {}", countKey);
                        continue;
                    }
                    String gifId = countKey.substring(prefixLength);
                    
                    // 获取点赞数并重置为0（原子操作）
                    Long likeCount = cacheService.getAndReset(countKey);
                    
                    if (likeCount != null && likeCount > 0) {
                        // 查询GIF记录
                        Gif gif = gifService.getById(gifId);
                        if (gif != null) {
                            // 增加点赞数（增量更新）
                            int newLikeCount = gif.getLikeCount() + likeCount.intValue();
                            gif.setLikeCount(newLikeCount);
                            gif.setUpdatedAt(LocalDateTime.now());
                            gifsToUpdate.add(gif);
                            log.debug("准备更新GIF(ID:{})点赞数增量: +{}, 新总数: {}", gifId, likeCount, newLikeCount);
                            
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
     * 同步用户喜欢记录到数据库
     *
     * <p>从Redis获取所有用户的喜欢/不喜欢记录，并将这些增量数据更新到数据库中。
     * 处理流程包括：</p>
     *
     * <ol>
     *   <li>获取所有有like/dislike数据的用户ID</li>
     *   <li>对每个用户获取其喜欢和不喜欢的GIF ID集合</li>
     *   <li>批量创建新的喜欢记录</li>
     *   <li>批量删除不喜欢的记录</li>
     *   <li>每处理{@link #USER_BATCH_SIZE}个有效用户执行一次批量数据库操作</li>
     * </ol>
     *
     * <p>操作完成后清空Redis中的相关数据，实现增量同步。</p>
     */
    private void syncUserLikesToDatabase() {
        try {
            // 获取所有有like/dislike数据的用户ID
            Set<String> userIds = cacheService.getUserIdsWithLikeData(USER_LIKE_KEY);

            if (userIds.isEmpty()) {
                log.info("没有有效的用户喜欢记录需要同步");
                return;
            }

            log.info("发现{}个用户的喜欢和不喜欢记录需要同步", userIds.size());
            // 批量处理，每批最多10个用户
            List<UserLike> batchNewLikes = new ArrayList<>();
            List<UserLike> batchDeleteLikes = new ArrayList<>();
            int processedCount = 0; // 实际处理的用户数量

            for (String userId : userIds) {
                try {
                    // 直接使用userId，构建userKey
                    Long userIdLong = Long.parseLong(userId);
                    String userKey = USER_LIKE_KEY + userId;

                    // 获取Redis中该用户喜欢的所有GIF ID (从set结构中获取like和dislike字段) 再清空set
                    String oldKey = userKey + ":old";

                    // 先备份数据，再获取并清空（确保数据安全）
                    String likeKey = userKey + ":like";
                    String dislikeKey = userKey + ":dislike";
                    String oldLikeKey = oldKey + ":like";
                    String oldDislikeKey = oldKey + ":dislike";

                    // 只有当key存在时才进行复制
                    if (cacheService.hasKey(likeKey)) {
                        cacheService.copy(likeKey, oldLikeKey);
                    }
                    if (cacheService.hasKey(dislikeKey)) {
                        cacheService.copy(dislikeKey, oldDislikeKey);
                    }

                    Map<String, Set<String>> likeDislike = cacheService.getSetLikeDislike(userKey, true);
                    Set<String> likedGifIds = likeDislike.get("like");
                    Set<String> dislikeGifIds = likeDislike.get("dislike");

                    // 如果两个集合都为空，则跳过（不计入processedCount）
                    if (CollectionUtil.isEmpty(likedGifIds) && CollectionUtil.isEmpty(dislikeGifIds)) {
                        log.info("用户{}没有需要同步的数据，跳过", userId);
                        continue;
                    }

                    // 不用查询数据库，直接保存和删除，因为redis保存的是增量数据
                    for (String gifId : likedGifIds) {
                        batchNewLikes.add(new UserLike().setUserId(userIdLong).setGifId(Long.parseLong(gifId)).setCreatedAt(LocalDateTime.now()));
                    }
                    for (String gifId : dislikeGifIds) {
                        batchDeleteLikes.add(new UserLike().setUserId(userIdLong).setGifId(Long.parseLong(gifId)));
                    }

                    processedCount++; // 只有成功处理的用户才计数

                    // 每10个有效用户保存一次
                    if (processedCount % USER_BATCH_SIZE == 0) {
                        try {
                            // 保存
                            if (!batchNewLikes.isEmpty()) {
                                userLikeService.saveBatch(batchNewLikes);
                                log.info("批量保存{}条喜欢记录", batchNewLikes.size());
                                batchNewLikes.clear();
                            }
                            // 删除
                            if (!batchDeleteLikes.isEmpty()) {
                                userLikeService.removeBatchByIds(batchDeleteLikes);
                                log.info("批量删除{}条不喜欢记录", batchDeleteLikes.size());
                                batchDeleteLikes.clear();
                            }
                        } catch (Exception e) {
                            log.error("批量处理数据库操作失败: {}", e.getMessage(), e);
                            // 清空批次数据，避免重复处理
                            batchNewLikes.clear();
                            batchDeleteLikes.clear();
                        }
                    }
                } catch (Exception e) {
                    log.error("处理用户喜欢记录失败: userId={}, 错误: {}", userId, e.getMessage());
                }
            }
            
            // 处理剩余记录
            try {
                if (!batchNewLikes.isEmpty()) {
                    userLikeService.saveBatch(batchNewLikes);
                    log.info("批量保存剩余的{}条喜欢记录", batchNewLikes.size());
                }
                if (!batchDeleteLikes.isEmpty()) {
                    userLikeService.removeBatchByIds(batchDeleteLikes);
                    log.info("批量删除剩余的{}条不喜欢记录", batchDeleteLikes.size());
                }
            } catch (Exception e) {
                log.error("处理剩余记录失败: {}", e.getMessage(), e);
            }

            log.info("用户喜欢记录同步完成，共处理{}个用户", processedCount);
        } catch (Exception e) {
            log.error("同步用户喜欢记录失败: {}", e.getMessage(), e);
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
}
