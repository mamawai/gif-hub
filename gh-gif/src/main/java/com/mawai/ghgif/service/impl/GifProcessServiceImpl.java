package com.mawai.ghgif.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.dtoMapper.GifParamMapper;
import com.mawai.ghgif.dto.GifDTO;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghgif.event.GifDeleteEvent;
import com.mawai.ghgif.service.GifProcessService;
import com.mawai.ghgif.util.R2FileUtils;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.GifDelete;
import com.mawai.ghmbplus.model.UserLike;
import com.mawai.ghmbplus.service.GifDeleteService;
import com.mawai.ghmbplus.service.GifService;
import com.mawai.ghmbplus.service.UserLikeService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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
    // 解决self-invocation问题
    // 循环依赖解决
    @Lazy
    @Autowired
    private GifProcessService self;
    private final CacheService cacheService;
    // 注入线程池
    private final Executor fileUploadExecutor;
    
    // S3客户端实例
    private S3Client s3Client;
    
    @PostConstruct
    public void init() {
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
            Object countObj = cacheService.get(TOTAL_GIF_COUNT_KEY);
            if (countObj instanceof Integer) {
                return ((Integer) countObj).longValue();
            } else if (countObj instanceof Long) {
                return (Long) countObj;
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
     * 判断用户是否喜欢此GIF
     * 情况：用户点击喜欢数据库没有记录，如果此时还没同步数据库 memberKey有数据，数据库没数据
     *       如果此时memberKey clear，如果此时数据库正在写入，用户调用了isLikeThis，由于memberKey都是空则查询oldKey
     *       如果此时点击了不喜欢，memberKey有数据，用户调用isLikeThis，应先查询memberKey
     *       memberKey优先级是最高的，如果memberKey没有数据则查询oldKey
     *
     * @param fileId GIF文件ID
     * @param userId 用户ID
     * @return 是否喜欢
     */
    @Override
    public boolean isLikeThis(String fileId, Long userId) {
        String memberKey = USER_LIKE_KEY + userId;
        if (cacheService.get(memberKey + ":like") == null && cacheService.get(memberKey + ":dislike") == null) {
            // 如果没有memberKey 直接查数据库
            return userLikeService.getOne(new LambdaQueryWrapper<UserLike>().eq(UserLike::getUserId, userId).eq(UserLike::getGifId, fileId)) != null;
        }
        Map<String, Set<String>> likesDislikes = cacheService.getSetLikeDislike(memberKey, false);
        Set<String> likes= likesDislikes.get("like");
        Set<String> dislikes = likesDislikes.get("dislike");
        if (likes != null && likes.contains(fileId)) {
            return true;
        } else if (dislikes != null && dislikes.contains(fileId)) {
            return false;
        } else {
            // 如果memberKey 没有数据则查询oldKey
            String oldKey = USER_LIKE_KEY + userId + ":old";
            if (cacheService.get(oldKey + ":like") == null && cacheService.get(oldKey + ":dislike") == null) {
                // 如果没有oldKey 则查询数据库
                return userLikeService.getOne(new LambdaQueryWrapper<UserLike>().eq(UserLike::getUserId, userId).eq(UserLike::getGifId, fileId)) != null;
            }
            // 如果有oldKey 用oldKey数据
            Map<String, Set<String>> oldLikesDislikes = cacheService.getSetLikeDislike(oldKey, false);
            Set<String> oldLikes = oldLikesDislikes.get("like");
            Set<String> oldDislikes = oldLikesDislikes.get("dislike");
            if (oldLikes != null && oldLikes.contains(fileId)) {
                return true;
            } else if (oldDislikes != null && oldDislikes.contains(fileId)) {
                return false;
            } else {
                // 如果oldKey没有数据 则查询数据库
                return userLikeService.getOne(new LambdaQueryWrapper<UserLike>().eq(UserLike::getUserId, userId).eq(UserLike::getGifId, fileId)) != null;
            }
        }
    }

    /**
     * 增加GIF总数（+1）
     */
    private void incrementTotalGifCount(Long userId) {
        try {
            cacheService.increment(TOTAL_GIF_COUNT_KEY, 1);
            cacheService.increment(TOTAL_GIF_COUNT_KEY + ":" + userId, 1, 60, TimeUnit.MINUTES);
            log.debug("GIF总数+1");
        } catch (Exception e) {
            log.error("增加GIF总数失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 减少GIF总数（-1）
     */
    private void decrementTotalGifCount(Long userId) {
        try {
            cacheService.decrement(TOTAL_GIF_COUNT_KEY, 1);
            cacheService.decrement(TOTAL_GIF_COUNT_KEY + ":" + userId, 1, 60, TimeUnit.MINUTES);
            log.debug("GIF总数-1");
        } catch (Exception e) {
            log.error("减少GIF总数失败: {}", e.getMessage(), e);
        }
    }

    // GIF大小限制，默认10MB
    @Value("${gif.max-size:10485760}")
    private long maxGifSize;

    private static final String LIKE_COUNT_KEY = "gif:like:";
    private static final String USER_LIKE_KEY = "user:like:";
    private static final String DOWNLOAD_COUNT_KEY = "gif:download:";
    private static final String TOTAL_GIF_COUNT_KEY = "gif:total:count"; // GIF总数缓存键
    private static final int EXPIRE_TIME = 2; // 过期时间
    private static final String GIF_DELETE_COUNT_KEY = "gif:delete:count"; // 删除计数器键
    private static final int DELETE_COUNT_THRESHOLD = 1; // 删除阈值
    private static final String DELETE_TASK_KEY = "gif:delete:task"; // 删除任务键

    /**
     * 上传单个GIF文件
     * @param file 文件对象
     * @param userId 用户ID
     * @param title 标题（可选）
     * @param description 描述（可选）
     * @return 文件访问URL
     * @throws FileUploadException 文件上传异常
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String r2uploadGif(MultipartFile file, Long userId, String title, String description) throws FileUploadException {
        // 校验文件类型
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".gif")) {
            throw new IllegalArgumentException("只能上传GIF格式的文件");
        }

        // 校验文件大小
        if (file.getSize() > maxGifSize) {
            throw new IllegalArgumentException("GIF文件大小超过" + (maxGifSize / 1024 / 1024) + "MB限制");
        }

        // 本地暂时不压缩(skip)
        // auto close stream
        try (InputStream inputStream = file.getInputStream()) {

            // 构建文件名
            String fileExtension = ".gif";
            String baseFileName = UUID.randomUUID().toString();

            // 创建基于用户ID的目录结构
            String userIdStr = String.valueOf(userId);
            String firstLevel = userIdStr.length() >= 2 ? userIdStr.substring(0, 2) : userIdStr;
            String folder = "gifs/" + firstLevel + "/" + userId + "/";
            String gifFileName = folder + baseFileName + fileExtension;

            // 创建PutObjectRequest
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(R2FileUtils.BUCKET_NAME)
                    .key(gifFileName)
                    .contentType(file.getContentType())
                    .build();

            // 上传文件 - 使用成员变量s3Client
            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(inputStream, file.getSize()));

            String fileUrl = "https://" + R2FileUtils.CDN_DOMAIN + "/" + gifFileName;
            try {
                // 保存到数据库
                Gif gif = new Gif();
                gif.setUserId(userId);
                gif.setTitle(StringUtils.hasText(title) ? title : originalFilename);
                gif.setDescription(description);
                gif.setFileUrl(fileUrl);
                gif.setFileSize((int) (file.getSize() / 1024));
                gif.setStatus((byte)1); // 默认状态为正常 -- 后续会改为审核 0
                gif.setViewCount(0);
                gif.setLikeCount(0);
                gif.setDownloadCount(0);
                gif.setCreatedAt(LocalDateTime.now());
                gif.setUpdatedAt(LocalDateTime.now());

                // 保存GIF信息
                gifService.save(gif);
                
                //TODO 增加GIF总数 -- 暂时放在这里等后期整合到Audit模块中，因为图片审核通过是在这个模块中完成
                incrementTotalGifCount(userId);

                return fileUrl;
            } catch (Exception e) {
                //TODO重试save
                log.error("保存GIF文件失败: 文件url{}", fileUrl);
                throw new IOException("保存GIF文件失败" + e.getMessage(), e);
            }
        } catch (IOException | S3Exception e) {
            log.error("R2文件上传失败: {}", e.getMessage(), e);
            throw new FileUploadException("文件上传失败，请稍后再试");
        }
    }

    /**
     * 批量上传GIF文件（包含压缩处理）
     * @param files 文件对象列表
     * @param userId 用户ID
     * @return 文件访问URL列表，失败的位置为空字符串
     * @throws IOException IO异常
     */
    @Override
    public List<String> batchUploadGif(List<MultipartFile> files, Long userId, List<String> titles, List<String> descriptions) throws IOException {
        // 校验文件类型
        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename != null && !originalFilename.toLowerCase().endsWith(".gif")) {
                throw new IllegalArgumentException("只能上传GIF格式的文件：" + originalFilename);
            }
        }
        
        int fileCount = files.size();
        
        // 创建固定大小的结果数组，保持顺序
        String[] urlArray = new String[fileCount];
        Exception[] exceptionArray = new Exception[fileCount];
        
        // 创建并行任务
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < fileCount; i++) {
            final int index = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    MultipartFile file = files.get(index);
                    String title = (titles != null && index < titles.size()) ? titles.get(index) : null;
                    String description = (descriptions != null && index < descriptions.size()) ? descriptions.get(index) : null;
                    
                    // 使用self引用调用事务方法，确保事务生效
                    String url = self.r2uploadGif(file, userId, title, description);
                    
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
                log.error("上传第{}个GIF文件失败: {}", i, exceptionArray[i].getMessage());
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
     * @param fileName 文件名
     * @return 是否更新成功
     */
    @Override
    public boolean updateDownloadCount(String fileName) {
        try {
            // 创建redis key
            String countKey = DOWNLOAD_COUNT_KEY + fileName;

            // 下载次数+1 并设置过期时间
            cacheService.increment(countKey, 1, EXPIRE_TIME, TimeUnit.MINUTES);

            return true;
        } catch (Exception e) {
            // 记录日志
            log.error("更新下载次数失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 更新点赞次数 - 使用redis实现
     * @param fileId 文件ID
     * @param userId 用户ID
     * @param isLike 点赞还是取消点赞
     * @return 是否更新成功
     */
    @Override
    public boolean updateLikeCount(String fileId, Long userId, Boolean isLike) {
        try {
            // 创建redis key
            String countKey = LIKE_COUNT_KEY + fileId;
            String memberKey = USER_LIKE_KEY + userId;
            // 根据喜欢还是不喜欢进行 increase 或者 decrease 并续期 10分钟
            // lua 脚本实现
            if (isLike) {
                // 点赞次数+1 向set的like键中添加
                cacheService.incrementAndAddToSet(countKey, memberKey, fileId, EXPIRE_TIME, TimeUnit.MINUTES);
            } else {
                // 点赞次数-1 向dislike的dislike键中添加
                cacheService.decrementAndRemoveFromSet(countKey, memberKey, fileId, EXPIRE_TIME, TimeUnit.MINUTES);
            }
            return true;
        } catch (Exception e) {
            // 记录日志
            log.error("更新点赞次数失败: {}", e.getMessage());
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
            gifDelete.setCreatedAt(LocalDateTime.now());
            gifDeleteService.save(gifDelete);

            // 删除GIF
            boolean success = gifService.removeById(fileId);
            //TODO 如果删除成功，减少GIF总数 -- 后期转移到Audit模块
            if (success) {
                decrementTotalGifCount(gif.getUserId());
                Long incremented = cacheService.increment(GIF_DELETE_COUNT_KEY, 1);
                if (incremented % DELETE_COUNT_THRESHOLD == 0) {
                    boolean acquired = cacheService.setIfAbsent(DELETE_TASK_KEY, "1", 5, TimeUnit.MINUTES);
                    if (acquired) {
                        try {
                            // 发布事件
                            SpringUtils.context().publishEvent(new GifDeleteEvent().setDelCount(incremented).setBatchSize(DELETE_COUNT_THRESHOLD));
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
     * 分页获取GIF文件列表
     * @param page 页码
     * @param pageSize 每页数量
     * @return GIF文件URL列表
     */
    @Override
    public List<GifDTO> listGifs(Integer page, Integer pageSize) {
        // 创建分页对象
        Page<Gif> pageParam = new Page<>(page, pageSize);
        // 查询总数量的时候禁用searchCount 性能很低 使用redis维护总数
        pageParam.setSearchCount(false);
        
        // 创建查询条件
        LambdaQueryWrapper<Gif> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Gif::getStatus, 1).orderByDesc(Gif::getCreatedAt);

        // 执行分页查询
        Page<Gif> resultPage = gifService.page(pageParam, queryWrapper);
        List<Gif> gifList = resultPage.getRecords();

        // 转换为DTO
        List<GifDTO> resList = new ArrayList<>();
        for (Gif gif : gifList) {
            GifDTO gifDTO = gifParamMapper.toGifDTO(gif);
            resList.add(gifDTO);
        }
        return resList;
    }
    
    /**
     * 获取用户上传的GIF文件列表
     * @param userId 用户ID
     * @param page 页码
     * @param pageSize 每页数量
     * @return GIF文件URL列表
     */
    @Override
    public Pair<List<GifDTO>, Long> listGifsByUser(Long userId, Integer page, Integer pageSize) {
        // 创建分页对象
        Page<Gif> pageParam = new Page<>(page, pageSize);
        // 先查redis看是否有这个用户上传gif数量的统计
        String countKey = TOTAL_GIF_COUNT_KEY + ":" + userId;
        Object countObject = cacheService.get(countKey);
        // 如果redis有这个用户上传gif数量的统计，则设置total，跳过searchCount, 如果数量发生变化，这个redis会自动更新, 如rediskey过期，则重新查询数据库
        if (countObject != null) {
            pageParam.setSearchCount(false);
            // 类型转换问题
            if (countObject instanceof Integer) {
                pageParam.setTotal(((Integer) countObject).longValue());
            } else if (countObject instanceof Long) {   
                pageParam.setTotal((Long) countObject);
            } else {
                log.warn("用户上传gif数量统计缓存数据异常: {}", countObject);
            }
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

        // 转换为DTO
        List<GifDTO> resList = new ArrayList<>();
        for (Gif gif : gifList) {
            GifDTO gifDTO = gifParamMapper.toGifDTO(gif);
            resList.add(gifDTO);
        }

        return new ImmutablePair<>(resList, resultPage.getTotal());
    }

    /**
     * 获取用户喜欢列表
     * @param userId 用户ID
     * @return 用户喜欢列表
     */
    @Override
    public List<GifDTO> getUserLikeList(Long userId) {
        // 获取用户喜欢列表 （从redis的hash结构和数据库中获取）
        String memberKey = USER_LIKE_KEY + userId;
        Map<String, Set<String>> likeDislike = cacheService.getSetLikeDislike(memberKey, false);
        Set<String> idSet = likeDislike.get("like");
        Set<String> filterIdSet = likeDislike.get("dislike");

        // 从数据库中获取
        LambdaQueryWrapper<UserLike> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserLike::getUserId, userId);
        List<UserLike> userLikeList = userLikeService.list(queryWrapper);

        // 合并redis和数据库中的gifId并且去重
        idSet.addAll(userLikeList.stream()
                .map(UserLike::getGifId)
                .map(String::valueOf)
                .toList());

        // 移除用户不喜欢的gifId
        idSet.removeAll(filterIdSet);

        // 根据gifId获取gif
        List<Gif> gifList = gifService.listByIds(idSet);

        // 将gifList转换为GifDTO列表
        List<GifDTO> resList = new ArrayList<>();
        for (Gif gif : gifList) {
            GifDTO gifDTO = gifParamMapper.toGifDTO(gif);
            resList.add(gifDTO);
        }
        return resList;
    }

}
                