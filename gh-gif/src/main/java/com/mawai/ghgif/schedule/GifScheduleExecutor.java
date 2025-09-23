//package com.mawai.ghgif.schedule;
//
//import cn.hutool.core.collection.CollectionUtil;
//import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
//import com.mawai.ghcommon.service.CacheService;
//import com.mawai.ghgif.event.GifDeleteEvent;
//import com.mawai.ghmbplus.model.Gif;
//import com.mawai.ghmbplus.model.GifDelete;
//import com.mawai.ghmbplus.model.GifDeleteFailed;
//import com.mawai.ghmbplus.model.UserLike;
//import com.mawai.ghmbplus.service.GifDeleteFailedService;
//import com.mawai.ghmbplus.service.GifDeleteService;
//import com.mawai.ghmbplus.service.GifService;
//import com.mawai.ghmbplus.service.UserLikeService;
//import com.mawai.ghgif.util.R2FileUtils;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.context.event.EventListener;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//import org.springframework.transaction.annotation.Transactional;
//import jakarta.annotation.PreDestroy;
//import software.amazon.awssdk.services.s3.S3Client;
//import software.amazon.awssdk.services.s3.model.*;
//
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.HashSet;
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//import java.util.concurrent.*;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.concurrent.atomic.AtomicInteger;
//
///**
// * Gif定时任务执行器
// */
//@Slf4j
//@RequiredArgsConstructor
////@Component 使用GifScheduleExecutorWithSQS进行解耦，如果不想用MQ可以用这个
//public class GifScheduleExecutor {
//
//    private final CacheService cacheService;
//    private final GifService gifService;
//    private final GifDeleteService gifDeleteService;
//    private final GifDeleteFailedService gifDeleteFailedService;
//
//    // 注入线程池
//    private final Executor scheduledExecutor;
//    // 虚拟线程执行器，用于数据获取（IO密集型）
//    private final Executor virtualDataFetchExecutor = Executors.newVirtualThreadPerTaskExecutor();
//    private final UserLikeService userLikeService;
//    private final R2FileUtils r2FileUtils;
//
//    private static final int SYNC_INTERVAL = 1; // 同步间隔
//    private static final int BATCH_SIZE = 100; // 批量处理大小
//    private static final int USER_BATCH_SIZE = 10;
//    private static final int MAX_CONCURRENT_DATA_FETCH = 20; // 最大并发数据获取线程数
//    private static final int DB_WORKER_THREADS = 3; // 数据库操作工作线程数
//    private static final int QUEUE_HIGH_WATER_MARK = 2000; // 队列高水位标记，用于背压控制
//    private static final int MAX_BACKPRESSURE_WAIT_SECONDS = 20; // 背压等待最大时间（秒）
//
//    private static final String DOWNLOAD_COUNT_KEY = "gif:download:";
//    private static final String LIKE_COUNT_KEY = "gif:like:";
//    private static final String VIEW_COUNT_KEY = "gif:view:";
//    private static final String USER_LIKE_CATEGORY_KEY = "user:like:category:";
//    private static final String USER_DISLIKE_KEY = "user:dislike:";
//
//    /**
//     * 定时任务：将Redis中GIF点赞次数和用户喜欢的GIF同步到数据库
//     *
//     * <p>该方法按照固定时间间隔（{@link #SYNC_INTERVAL}分钟）执行，
//     * 将Redis缓存中的点赞数据持久化到数据库中。主要包含两部分：</p>
//     *
//     * <ul>
//     *   <li>同步GIF下载次数到数据库（{@link #syncDownloadCountToDatabase()}）</li>
//     *   <li>同步GIF点赞计数到数据库（{@link #syncLikeCountsToDatabase()}）</li>
//     *   <li>同步用户喜欢记录到数据库（{@link #syncUserLikesToDatabaseConcurrent()}）</li>
//     * </ul>
//     *
//     * <p>任务执行过程记录完整日志，包括开始、完成和异常信息。</p>
//     *
//     * @see #syncLikeCountsToDatabase() 点赞数据同步实现
//     * @see #syncUserLikesToDatabaseConcurrent() () 用户喜欢记录同步实现
//     * @see CacheService#getKeysWithPattern(String) 获取符合模式的Redis键
//     */
//    @Scheduled(fixedRate = SYNC_INTERVAL * 60 * 1000) // 转换为毫秒
//    public void syncGifLikeCount() {
//        log.info("开始同步Redis中的GIF点赞数据到数据库...");
//        // 使用CompletableFuture并发执行，不等待完成
//        CompletableFuture
//            .runAsync(this::syncDownloadCountToDatabase, scheduledExecutor)
//            .exceptionally(e -> {
//                log.error("同步下载计数失败: {}", e.getMessage(), e);
//                return null;
//            });
//
//        CompletableFuture
//            .runAsync(this::syncViewCountsToDatabase, scheduledExecutor)
//            .exceptionally(e -> {
//                log.error("同步查看计数失败: {}", e.getMessage(), e);
//                return null;
//            });
//
//        CompletableFuture
//            .runAsync(this::syncLikeCountsToDatabase, scheduledExecutor)
//            .exceptionally(e -> {
//                log.error("同步点赞计数失败: {}", e.getMessage(), e);
//                return null;
//            });
//
//        CompletableFuture
//            .runAsync(this::syncUserLikesToDatabaseConcurrent, scheduledExecutor)
//            .exceptionally(e -> {
//                log.error("同步用户喜欢记录失败: {}", e.getMessage(), e);
//                return null;
//            });
//
//        // 无需等待所有任务完成，直接返回
//        log.info("已启动GIF数据同步任务，将在{}分钟后再次触发同步", SYNC_INTERVAL);
//    }
//
//    /**
//     * 同步下载次数到数据库
//     */
//    private void syncDownloadCountToDatabase() {
//        try {
//            // 一次Redis请求完成：扫描匹配的key + 过滤非零值 + 获取值 + 重置为0
//            Map<String, Long> nonZeroCounters = cacheService.scanAndResetNonZeroCounters(DOWNLOAD_COUNT_KEY + "*");
//
//            if (nonZeroCounters.isEmpty()) {
//                log.info("没有GIF下载记录需要同步");
//                return;
//            }
//
//            log.info("发现{}个GIF下载记录需要同步", nonZeroCounters.size());
//            int prefixLength = DOWNLOAD_COUNT_KEY.length();
//
//            // 批量处理，每批最多100条记录
//            List<Gif> gifsToUpdate = new ArrayList<>();
//
//            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
//                String countKey = entry.getKey();
//                Long downloadCount = entry.getValue();
//
//                try {
//                    // 提取ID - 使用前缀长度直接获取
//                    if (countKey.length() <= prefixLength) {
//                        log.warn("无效的键格式: {}", countKey);
//                        continue;
//                    }
//                    String gifId = countKey.substring(prefixLength);
//
//                    if (downloadCount != null && downloadCount > 0) {
//                        // 查询GIF记录
//                        Gif gif = gifService.getById(gifId);
//                        if (gif != null) {
//                            // 增加下载次数（增量更新）
//                            int newDownloadCount = gif.getDownloadCount() + downloadCount.intValue();
//                            gif.setDownloadCount(newDownloadCount);
//                            gif.setUpdatedAt(LocalDateTime.now());
//                            gifsToUpdate.add(gif);
//                            log.debug("准备更新GIF(ID:{})下载数增量: +{}, 新总数: {}", gifId, downloadCount, newDownloadCount);
//
//                            // 达到批量大小时更新数据库
//                            if (gifsToUpdate.size() >= BATCH_SIZE) {
//                                gifService.updateBatchById(gifsToUpdate);
//                                log.info("已批量更新{}个GIF下载数", gifsToUpdate.size());
//                                gifsToUpdate.clear();
//                            }
//                        }
//                    }
//
//                } catch (Exception e) {
//                    log.error("处理下载键失败: {}, 错误: {}", countKey, e.getMessage());
//                }
//            }
//
//            // 处理剩余记录
//            if (!gifsToUpdate.isEmpty()) {
//                gifService.updateBatchById(gifsToUpdate);
//                log.info("已批量更新剩余的{}个GIF下载数", gifsToUpdate.size());
//            }
//        } catch (Exception e) {
//            log.error("同步下载次数失败: {}", e.getMessage(), e);
//        }
//    }
//
//
//    /**
//     * 同步查看次数到数据库
//     */
//    private void syncViewCountsToDatabase() {
//        try {
//            // 一次Redis请求完成：扫描匹配的key + 过滤非零值 + 获取值 + 重置为0
//            Map<String, Long> nonZeroCounters = cacheService.scanAndResetNonZeroCounters(VIEW_COUNT_KEY + "*");
//
//            if (nonZeroCounters.isEmpty()) {
//                log.info("没有GIF查看记录需要同步");
//                return;
//            }
//
//            log.info("发现{}个GIF查看记录需要同步", nonZeroCounters.size());
//            int prefixLength = VIEW_COUNT_KEY.length();
//
//            // 批量处理，每批最多100条记录
//            List<Gif> gifsToUpdate = new ArrayList<>();
//
//            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
//                String countKey = entry.getKey();
//                Long viewCount = entry.getValue();
//
//                try {
//                    // 提取ID - 使用前缀长度直接获取
//                    if (countKey.length() <= prefixLength) {
//                        log.info("无效的键格式: {}", countKey);
//                        continue;
//                    }
//                    String gifId = countKey.substring(prefixLength);
//
//                    if (viewCount != null && viewCount != 0) {
//                        // 查询GIF记录
//                        Gif gif = gifService.getById(gifId);
//                        if (gif != null) {
//                            // 增加查看数（增量更新）
//                            int newViewCount = gif.getViewCount() + viewCount.intValue();
//                            gif.setViewCount(newViewCount);
//                            gif.setUpdatedAt(LocalDateTime.now());
//                            gifsToUpdate.add(gif);
//                            log.info("准备更新GIF(ID:{})查看数增量: {}, 新总数: {}", gifId, viewCount, newViewCount);
//
//                            // 达到批量大小时更新数据库
//                            if (gifsToUpdate.size() >= BATCH_SIZE) {
//                                gifService.updateBatchById(gifsToUpdate);
//                                log.info("已批量更新{}个GIF查看数", gifsToUpdate.size());
//                                gifsToUpdate.clear();
//                            }
//                        }
//                    }
//                } catch (Exception e) {
//                    log.error("处理查看键失败: {}, 错误: {}", countKey, e.getMessage());
//                }
//            }
//
//            // 处理剩余记录
//            if (!gifsToUpdate.isEmpty()) {
//                gifService.updateBatchById(gifsToUpdate);
//                log.info("已批量更新剩余的{}个GIF查看数", gifsToUpdate.size());
//            }
//        } catch (Exception e) {
//            log.error("同步GIF查看数数据失败: {}", e.getMessage(), e);
//        }
//    }
//
//    /**
//     * 同步点赞数据到数据库
//     *
//     * <p>从Redis获取所有需要同步的GIF点赞计数数据，并将其增量更新到数据库中。
//     * 处理流程包括：</p>
//     *
//     * <ol>
//     *   <li>扫描匹配的key + 过滤非零值 + 获取值 + 重置为0</li>
//     *   <li>提取每个键中的GIF ID</li>
//     *   <li>获取点赞计数并原子性地重置Redis计数</li>
//     *   <li>查询对应GIF记录并增加点赞数（增量更新）</li>
//     *   <li>批量更新数据库（每{@link #BATCH_SIZE}条记录一批）</li>
//     * </ol>
//     *
//     * <p>整个过程有完整的日志记录，包括同步数量和异常处理。</p>
//     */
//    private void syncLikeCountsToDatabase() {
//        try {
//            // 一次Redis请求完成：扫描匹配的key + 过滤非零值 + 获取值 + 重置为0
//            Map<String, Long> nonZeroCounters = cacheService.scanAndResetNonZeroCounters(LIKE_COUNT_KEY + "*");
//
//            if (nonZeroCounters.isEmpty()) {
//                log.info("没有GIF点赞和取消点赞记录需要同步");
//                return;
//            }
//
//            log.info("发现{}个GIF点赞和取消点赞记录需要同步", nonZeroCounters.size());
//            int prefixLength = LIKE_COUNT_KEY.length();
//
//            // 批量处理，每批最多100条记录
//            List<Gif> gifsToUpdate = new ArrayList<>();
//
//            for (Map.Entry<String, Long> entry : nonZeroCounters.entrySet()) {
//                String countKey = entry.getKey();
//                Long likeCount = entry.getValue();
//
//                try {
//                    // 提取ID - 使用前缀长度直接获取
//                    if (countKey.length() <= prefixLength) {
//                        log.info("无效的键格式: {}", countKey);
//                        continue;
//                    }
//                    String gifId = countKey.substring(prefixLength);
//
//                    if (likeCount != null && likeCount != 0) {
//                        // 查询GIF记录
//                        Gif gif = gifService.getById(gifId);
//                        if (gif != null) {
//                            // 增加点赞数（增量更新）
//                            int newLikeCount = gif.getLikeCount() + likeCount.intValue();
//                            gif.setLikeCount(newLikeCount);
//                            gif.setUpdatedAt(LocalDateTime.now());
//                            gifsToUpdate.add(gif);
//                            log.info("准备更新GIF(ID:{})点赞数增量: {}, 新总数: {}", gifId, likeCount, newLikeCount);
//
//                            // 达到批量大小时更新数据库
//                            if (gifsToUpdate.size() >= BATCH_SIZE) {
//                                gifService.updateBatchById(gifsToUpdate);
//                                log.info("已批量更新{}个GIF点赞数", gifsToUpdate.size());
//                                gifsToUpdate.clear();
//                            }
//                        }
//                    }
//                } catch (Exception e) {
//                    log.error("处理点赞键失败: {}, 错误: {}", countKey, e.getMessage());
//                }
//            }
//
//            // 处理剩余记录
//            if (!gifsToUpdate.isEmpty()) {
//                gifService.updateBatchById(gifsToUpdate);
//                log.info("已批量更新剩余的{}个GIF点赞数", gifsToUpdate.size());
//            }
//        } catch (Exception e) {
//            log.error("同步GIF点赞数据失败: {}", e.getMessage(), e);
//        }
//    }
//
//    /**
//     * 使用虚拟线程优化的用户喜欢记录同步到数据库
//     *
//     * <p>该方法采用生产者-消费者模式，实现高效的用户喜欢记录同步。从Redis缓存中获取用户的点赞和取消点赞数据，
//     * 并将这些数据批量同步到MySQL数据库中。</p>
//     *
//     * <h3>架构设计</h3>
//     * <ul>
//     *   <li><strong>生产者</strong>：使用虚拟线程并发从Redis获取用户数据，充分利用虚拟线程在IO密集型操作中的优势</li>
//     *   <li><strong>消费者</strong>：使用固定数量的平台线程处理数据库操作，避免数据库连接池竞争和过载</li>
//     *   <li><strong>队列</strong>：使用无界{@link LinkedBlockingQueue}解耦生产和消费，实现流水线处理</li>
//     * </ul>
//     *
//     * <h3>处理流程</h3>
//     * <ol>
//     *   <li>获取所有有点赞/取消点赞数据的用户ID列表</li>
//     *   <li>启动{@value #DB_WORKER_THREADS}个数据库消费者线程</li>
//     *   <li>使用虚拟线程批量并发获取用户数据，每批最多{@value #MAX_CONCURRENT_DATA_FETCH}个并发任务</li>
//     *   <li>生产者将获取到的数据放入队列，消费者从队列取数据并批量处理数据库操作</li>
//     *   <li>生产者完成后设置标志位，消费者检测到标志位且队列为空时优雅退出</li>
//     * </ol>
//     *
//     * <h3>性能优化</h3>
//     * <ul>
//     *   <li><strong>并发控制</strong>：限制Redis并发数为{@value #MAX_CONCURRENT_DATA_FETCH}，避免内存太大</li>
//     *   <li><strong>批量处理</strong>：消费者每处理{@value #USER_BATCH_SIZE}个用户数据执行一次数据库批量操作</li>
//     *   <li><strong>流水线处理</strong>：生产和消费并发进行，总处理时间约等于max(Redis获取时间, 数据库写入时间)</li>
//     *   <li><strong>内存控制</strong>：边生产边消费，避免内存中积累大量数据</li>
//     * </ul>
//     *
//     * <h3>异常处理</h3>
//     * <ul>
//     *   <li>单个用户数据获取失败不影响其他用户的处理</li>
//     *   <li>单个消费者线程异常不影响其他消费者线程</li>
//     *   <li>使用{@link AtomicBoolean}标志位确保消费者能够优雅退出</li>
//     * </ul>
//     *
//     * @see #fetchUserDataConcurrently(List, BlockingQueue) 生产者数据获取逻辑
//     * @see #startDatabaseConsumersWithFlag(BlockingQueue, AtomicInteger, AtomicBoolean) 消费者启动逻辑
//     * @see #executeBatchDatabaseOperations(List, List) 批量数据库操作
//     * @since 1.0
//     */
//    private void syncUserLikesToDatabaseConcurrent() {
//        try {
//            // 获取所有有like/dislike数据的用户ID
//            Set<String> userIds = cacheService.getUserIdsWithLikeDataOptimized(USER_LIKE_CATEGORY_KEY, USER_DISLIKE_KEY);
//
//            if (userIds.isEmpty()) {
//                log.info("没有有效的用户喜欢记录需要同步");
//                return;
//            }
//
//            log.info("发现{}个用户的喜欢和不喜欢记录需要同步，使用生产者-消费者模式处理", userIds.size());
//
//            // 创建无界队列用于传递处理好的数据，避免数据丢失
//            BlockingQueue<UserLikeData> dataQueue = new LinkedBlockingQueue<>();
//            AtomicInteger processedUsers = new AtomicInteger(0);
//            AtomicBoolean producerFinished = new AtomicBoolean(false);
//
//            // 启动固定数量的数据库消费者线程
//            List<CompletableFuture<Void>> dbConsumerFutures = startDatabaseConsumersWithFlag(dataQueue, processedUsers, producerFinished);
//
//            // 使用虚拟线程并发获取用户数据（生产者）
//            List<String> userIdList = new ArrayList<>(userIds);
//            List<CompletableFuture<Void>> dataFetchFutures = new ArrayList<>();
//
//            // 控制并发数，避免Redis压力过大
//            for (int i = 0; i < userIdList.size(); i += MAX_CONCURRENT_DATA_FETCH) {
//                int endIndex = Math.min(i + MAX_CONCURRENT_DATA_FETCH, userIdList.size());
//                List<String> batch = userIdList.subList(i, endIndex);
//
//                CompletableFuture<Void> future = CompletableFuture
//                    .runAsync(() -> fetchUserDataConcurrently(batch, dataQueue), virtualDataFetchExecutor)
//                    .exceptionally(ex -> {
//                        log.error("处理用户数据失败: {}", ex.getMessage(), ex);
//                        return null;
//                    });
//
//                dataFetchFutures.add(future);
//            }
//
//            // 等待所有数据获取完成
//            CompletableFuture.allOf(dataFetchFutures.toArray(new CompletableFuture[0])).join();
//
//            // 标记生产者完成
//            producerFinished.set(true);
//            log.info("所有生产者完成，等待消费者处理完剩余数据");
//
//            // 等待所有数据库操作完成
//            CompletableFuture.allOf(dbConsumerFutures.toArray(new CompletableFuture[0])).join();
//
//            log.info("生产者-消费者模式用户喜欢记录同步完成，共处理{}个用户", processedUsers.get());
//        } catch (Exception e) {
//            log.error("生产者-消费者模式同步用户喜欢记录失败: {}", e.getMessage(), e);
//        }
//    }
//
//
//    /**
//     * 执行批量数据库操作
//     */
//    private void executeBatchDatabaseOperations(List<UserLike> batchNewLikes, List<UserLike> batchDeleteLikes) {
//        try {
//            if (!batchNewLikes.isEmpty()) {
//                userLikeService.insertOrUpdateBatchByUniqueKey(batchNewLikes);
//                log.info("批量保存/更新{}条喜欢记录", batchNewLikes.size());
//            }
//
//            if (!batchDeleteLikes.isEmpty()) {
//                for (UserLike userLike : batchDeleteLikes) {
//                    LambdaQueryWrapper<UserLike> deleteWrapper = new LambdaQueryWrapper<>();
//                    deleteWrapper.eq(UserLike::getUserId, userLike.getUserId())
//                                .eq(UserLike::getGifId, userLike.getGifId());
//                    userLikeService.remove(deleteWrapper);
//                }
//                log.info("批量删除{}条不喜欢记录", batchDeleteLikes.size());
//            }
//        } catch (Exception e) {
//            log.error("批量数据库操作失败", e);
//        }
//    }
//
//     /**
//     * 监听GifDeleteEvent事件，清理删除记录表中的数据 -- 删除R2层面的垃圾文件
//     * 不加try-catch，避免异常回滚失败
//     *
//     * <p>该方法清理已被标记为删除的GIF文件，流程如下：</p>
//     *
//     * <ol>
//     *   <li>监听GifDeleteEvent事件</li>
//     *   <li>从删除记录表中获取需要删除的记录</li>
//     *   <li>批量删除对应的R2存储上的实际文件</li>
//     *   <li>R2文件删除成功后，再从数据库中删除这些记录</li>
//     * </ol>
//     *
//     * <p>该任务确保系统垃圾文件得到定期清理，释放存储空间</p>
//     */
//    @Transactional(rollbackFor = Exception.class)
//    @EventListener
//    public void clearDeletedGifs(GifDeleteEvent event) {
//        log.info("开始清理删除记录表中的数据和R2垃圾文件...总删除数量为:{}", event.getDelCount());
//
//        // 调用服务层方法获取需要删除的记录（不会从数据库中删除）
//        List<GifDelete> deleteRecords = gifDeleteService.getDeleteRecords(event.getBatchSize());
//
//        if (deleteRecords.isEmpty()) {
//            log.info("没有需要清理的删除记录");
//            return;
//        }
//
//        log.info("获取到{}条需要从R2删除的文件记录", deleteRecords.size());
//
//        // 获取S3Client
//        S3Client s3Client = r2FileUtils.getS3Client();
//
//        // 准备批量删除对象
//        List<ObjectIdentifier> objectsToDelete = new ArrayList<>();
//        int failUrls = 0;
//
//        // 收集要删除的对象标识符
//        for (GifDelete record : deleteRecords) {
//            String fileUrl = record.getFileUrl();
//            String objectKey = extractObjectKeyFromUrl(fileUrl);
//
//            if (objectKey != null) {
//                objectsToDelete.add(
//                    ObjectIdentifier.builder()
//                        .key(objectKey)
//                        .build()
//                );
//            } else {
//                failUrls++;
//                log.error("无法从URL提取对象键: {}", fileUrl);
//            }
//        }
//
//        if (objectsToDelete.isEmpty()) {
//            log.info("没有有效的对象需要删除");
//            return;
//        }
//
//        // 执行批量删除
//        DeleteObjectsResponse deleteResponse = s3Client.deleteObjects(
//                // 创建批量删除请求
//                DeleteObjectsRequest.builder()
//                        .bucket(R2FileUtils.BUCKET_NAME)
//                        .delete(
//                                Delete.builder()
//                                        .objects(objectsToDelete)
//                                        .quiet(true) // 安静模式，只返回删除失败的对象
//                                        .build()
//                        )
//                        .build()
//        );
//
//        // 处理删除结果
//        Set<String> failedKeys = new HashSet<>();
//
//        // 如果有错误，收集失败的key
//        if (deleteResponse.hasErrors() && !deleteResponse.errors().isEmpty()) {
//            deleteResponse.errors().forEach(error -> {
//                failedKeys.add("https://mynnmy.top/" + error.key());
//                log.error("删除对象失败: 键={}, 错误码={}, 消息={}",
//                    error.key(), error.code(), error.message());
//            });
//        }
//
//        // 记录删除结果
//        log.info("成功从R2批量删除了{}个文件，失败{}个", deleteRecords.size() - failedKeys.size(), failedKeys.size());
//        // 记录无法解析URL的情况
//        if (failUrls > 0) {
//            log.warn("有{}个URL无法解析为对象键", failUrls);
//        }
//
//        List<Long> successIds = deleteRecords.stream()
//                .filter(record -> !failedKeys.contains(record.getFileUrl()))
//                .map(GifDelete::getId).toList();
//
//        // 只有在成功删除了R2文件后，才从数据库中删除记录
//        if (!successIds.isEmpty()) {
//            boolean dbDeleteSuccess = gifDeleteService.removeDeleteRecords(successIds);
//            if (dbDeleteSuccess) {
//                log.info("成功从数据库中删除了{}条记录", successIds.size());
//            } else {
//                log.error("从数据库中删除记录失败");
//            }
//        }
//
//        // 将failedKeys记录存到新表 --- 后续人工排查
//        if (!failedKeys.isEmpty()) {
//            gifDeleteFailedService.saveBatch(
//                failedKeys.stream()
//                        .map(key -> new GifDeleteFailed()
//                                .setFileUrl("https://mynnmy.top/" + key)
//                                .setCreatedAt(LocalDateTime.now()
//                                )
//                        ).toList()
//            );
//        }
//    }
//
//    /**
//     * 从URL中提取S3对象键
//     * 例如从 <a href="">https://mynnmy.top/gifs/01/123/abc.gif</a> 提取 "gifs/01/123/abc.gif"
//     *
//     * @param url 文件URL
//     * @return 对象键
//     */
//    private String extractObjectKeyFromUrl(String url) {
//        if (url == null || url.isEmpty()) {
//            return null;
//        }
//        // 使用固定前缀截取，效率更高 10000次 0.5ms 而 split再取parts[3] 6ms
//        // 这里写死固定长度截取 -- https://mynnmy.top/
//        return url.substring(19);
//    }
//
//    /**
//     * 用户喜欢数据传输对象
//     */
//    private record UserLikeData(
//            String userId,
//            List<UserLike> newLikes,
//            List<UserLike> deleteLikes) {
//
//    }
//
//    /**
//     * 启动固定数量的数据库消费者线程（使用标志位控制）
//     */
//    private List<CompletableFuture<Void>> startDatabaseConsumersWithFlag(BlockingQueue<UserLikeData> dataQueue,
//                                                                        AtomicInteger processedUsers,
//                                                                        AtomicBoolean producerFinished) {
//        List<CompletableFuture<Void>> futures = new ArrayList<>();
//
//        for (int i = 0; i < DB_WORKER_THREADS; i++) {
//            final int consumerId = i;
//            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
//                List<UserLike> batchNewLikes = new ArrayList<>();
//                List<UserLike> batchDeleteLikes = new ArrayList<>();
//                int localProcessedCount = 0;
//
//                log.info("数据库消费者线程{}启动", consumerId);
//
//                try {
//                    while (true) {
//                        UserLikeData data;
//
//                        // 尝试从队列获取数据，带超时
//                        try {
//                            data = dataQueue.poll(100, TimeUnit.MILLISECONDS);
//                        } catch (InterruptedException e) {
//                            Thread.currentThread().interrupt();
//                            break;
//                        }
//
//                        if (data != null) {
//                            // 收集数据到批次中
//                            if (data.newLikes() != null) {
//                                batchNewLikes.addAll(data.newLikes());
//                            }
//                            if (data.deleteLikes() != null) {
//                                batchDeleteLikes.addAll(data.deleteLikes());
//                            }
//
//                            localProcessedCount++;
//                            processedUsers.incrementAndGet();
//
//                            // 每处理USER_BATCH_SIZE个用户执行一次数据库操作
//                            if (localProcessedCount % USER_BATCH_SIZE == 0) {
//                                executeBatchDatabaseOperations(batchNewLikes, batchDeleteLikes);
//                                batchNewLikes.clear();
//                                batchDeleteLikes.clear();
//                            }
//                        } else {
//                            // 检查生产者是否完成 -- producerFinished.get(); 并且队列为空
//                            if (producerFinished.get() && dataQueue.isEmpty()) {
//                                log.info("数据库消费者线程{}检测到生产者完成且队列为空，准备退出", consumerId);
//                                break;
//                            }
//                            // 否则继续等待
//                        }
//                    }
//
//                    // 处理剩余数据
//                    if (!batchNewLikes.isEmpty() || !batchDeleteLikes.isEmpty()) {
//                        executeBatchDatabaseOperations(batchNewLikes, batchDeleteLikes);
//                    }
//
//                    log.info("数据库消费者线程{}完成，处理了{}个用户", consumerId, localProcessedCount);
//
//                } catch (Exception e) {
//                    log.error("数据库消费者线程{}执行失败", consumerId, e);
//                }
//            });
//
//            futures.add(future);
//        }
//
//        return futures;
//    }
//
//    /**
//     * 等待队列有空间，带超时机制避免无限等待
//     * @param dataQueue 数据队列
//     * @param userId 用户ID（用于日志）
//     * @return true表示队列有空间，false表示超时放弃
//     */
//    @SuppressWarnings("BusyWait")
//    private boolean waitForQueueSpace(BlockingQueue<UserLikeData> dataQueue, String userId) {
//        long startTime = System.currentTimeMillis();
//        long maxWaitTime = MAX_BACKPRESSURE_WAIT_SECONDS * 1000L;
//
//        // 队列大小超过高水位标记，开始等待 -- 最多等待20秒
//        while (dataQueue.size() > QUEUE_HIGH_WATER_MARK) {
//            // 计算已等待时间
//            long elapsedTime = System.currentTimeMillis() - startTime;
//            if (elapsedTime > maxWaitTime) {
//                log.error("用户{}数据处理超时放弃，队列大小: {}, 等待时间: {}ms",
//                    userId, dataQueue.size(), elapsedTime);
//                return false;
//            }
//
//            try {
//                Thread.sleep(200); // 增加到200ms，减少检查频率
//                if (elapsedTime % 5000 == 0) { // 每5秒记录一次日志
//                    log.warn("用户{}等待队列空间，当前队列大小: {}, 已等待: {}ms",
//                        userId, dataQueue.size(), elapsedTime);
//                }
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                log.warn("用户{}等待队列空间被中断", userId);
//                return false;
//            }
//        }
//        return true;
//    }
//
//    /**
//     * 使用虚拟线程并发获取用户数据（带背压控制）
//     */
//    private void fetchUserDataConcurrently(List<String> userIds, BlockingQueue<UserLikeData> dataQueue) {
//        List<CompletableFuture<Void>> futures = userIds.stream()
//            .map(userId -> CompletableFuture.runAsync(() -> {
//                try {
//                    // 背压控制：如果队列过大，等待一段时间后放弃（20s）
//                    if (!waitForQueueSpace(dataQueue, userId)) {
//                        return; // 超时放弃处理这个用户
//                    }
//
//                    UserLikeData data = fetchSingleUserData(userId);
//                    if (data != null) {
//                        // 使用put()确保数据不会丢失，会阻塞直到有空间
//                        dataQueue.put(data);
//                    }
//                } catch (InterruptedException e) {
//                    Thread.currentThread().interrupt();
//                    log.warn("获取用户{}数据被中断", userId);
//                } catch (Exception e) {
//                    log.error("获取用户{}数据失败: {}", userId, e.getMessage(), e);
//                }
//            }, virtualDataFetchExecutor))
//            .toList();
//
//        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
//    }
//
//    /**
//     * 获取单个用户的数据（纯IO操作，适合虚拟线程）
//     */
//    private UserLikeData fetchSingleUserData(String userId) {
//        try {
//            Long userIdLong = Long.parseLong(userId);
//            String likeHashKey = USER_LIKE_CATEGORY_KEY + userId;
//            String dislikeSetKey = USER_DISLIKE_KEY + userId;
//
//            Map<String, Long> likedGifMapping = cacheService.getLikeCategoryIdsSafely(likeHashKey, true);
//            Set<String> dislikeGifIds = cacheService.getStringSetSafely(dislikeSetKey, true);
//
//            // 没有点赞和取消点赞记录，直接返回null
//            if (CollectionUtil.isEmpty(likedGifMapping) && CollectionUtil.isEmpty(dislikeGifIds)) {
//                return null;
//            }
//
//            List<UserLike> newLikes = new ArrayList<>();
//            List<UserLike> deleteLikes = new ArrayList<>();
//
//            for (Map.Entry<String, Long> entry : likedGifMapping.entrySet()) {
//                try {
//                    String gifId = entry.getKey();
//                    Long categoryId = entry.getValue();
//
//                    UserLike userLike = new UserLike()
//                        .setUserId(userIdLong)
//                        .setGifId(Long.parseLong(gifId))
//                        .setUserLikeCategoryId(categoryId);
//
//                    newLikes.add(userLike);
//                } catch (Exception e) {
//                    log.error("处理用户{}的点赞记录失败: gifId={}", userId, entry.getKey(), e);
//                }
//            }
//
//            for (String gifId : dislikeGifIds) {
//                try {
//                    UserLike userLike = new UserLike()
//                            .setUserId(userIdLong)
//                            .setGifId(Long.parseLong(gifId));
//
//                    deleteLikes.add(userLike);
//                } catch (Exception e) {
//                    log.error("处理用户{}的取消点赞记录失败: gifId={}", userId, gifId, e);
//                }
//            }
//
//            return new UserLikeData(userId, newLikes, deleteLikes);
//
//        } catch (Exception e) {
//            log.error("获取用户{}数据失败: {}", userId, e.getMessage(), e);
//            return null;
//        }
//    }
//
//    /**
//     * 应用关闭时清理资源
//     */
//    @PreDestroy
//    public void cleanup() {
//        log.info("GifScheduleExecutor 清理完成");
//    }
//}
