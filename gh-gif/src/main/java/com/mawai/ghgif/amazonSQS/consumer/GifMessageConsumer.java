package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.GifMessage;
import com.mawai.ghgif.service.AmazonSQSService;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.GifDelete;
import com.mawai.ghmbplus.model.GifTag;
import com.mawai.ghmbplus.model.Tag;
import com.mawai.ghmbplus.service.GifDeleteService;
import com.mawai.ghmbplus.service.GifService;
import com.mawai.ghmbplus.service.GifTagService;
import com.mawai.ghmbplus.dao.TagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.sqs.model.Message;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class GifMessageConsumer implements MessageConsumer {

    private final GifService gifService;
    private final GifDeleteService gifDeleteService;
    private final CacheService cacheService;
    @Lazy
    @Autowired
    private AmazonSQSService amazonSQSService;
    private final TagMapper tagMapper;
    private final GifTagService gifTagService;
    private static final String TOTAL_GIF_COUNT_KEY = "gif:total:count"; // GIF总数缓存键
    private static final String GIF_MSG = "gif:msg:";
    private static final String HANDLE_GIF_MSG_FAIL = "gif:msg:fail:";
    @Value("${aws.sqs.base-queue-url}")
    private String queueUrl;


    @Override
    public Consumer<Message> handleMessage() {
        return message -> SpringUtils.getAopProxy(this).handle(message);
    }

    @Transactional(rollbackFor = Exception.class)
    public void handle(Message message) {
        String body = message.body();
        log.info("GifMessageConsumer: 处理GIF消息: {}", body);

        // 判断是否poll的是空消息
        if (body.isBlank()) return;

        GifMessage gifMessage = null;
        try {
            gifMessage = JSONUtil.toBean(body, GifMessage.class);
            Long userId = gifMessage.getUserId();
            String key = GIF_MSG + gifMessage.getFileUrl() + ":" + gifMessage.getUserId();

            // 幂等性校验
            if (cacheService.hasKey(key)) {
                log.info("GIF消息已处理过，丢弃消息: {}", message);
                amazonSQSService.delete(message.receiptHandle(), queueUrl);
                return;
            }

            // 保存GIF信息
            Gif gif = buildGifFromMessage(gifMessage);
            if (!gifService.insertOne(gif)) {
                throw new RuntimeException("保存GIF文件失败");
            }
            log.info("保存GIF文件成功，ID: {}", gif.getId());

            // 保存标签信息（如果存在）
            if (gifMessage.getTags() != null && !gifMessage.getTags().isEmpty()) {
                saveGifTags(gif.getId(), gifMessage.getTags());
            }

            // 模拟抛出异常 throw new RuntimeException("模拟异常");

            // 事务成功后的回调
            registerAfterCommit(() -> {
                try {
                    // 增加GIF总数
                    incrementTotalGifCount(userId);
                    // 删除SQS消息
                    amazonSQSService.delete(message.receiptHandle(), queueUrl);
                    log.info("SQS消息删除成功");
                } catch (Exception e) {
                    log.error("删除SQS消息失败: {}", e.getMessage(), e);
                }
            });

            // 处理成功加锁
            cacheService.set(key, "1", 6L, TimeUnit.HOURS);

        } catch (Exception e) {
            log.error("保存GIF文件失败，Message信息: {}", message, e);

            // 记录失败信息 -- 等待clearDeletedGifs删除r2文件
            // 通过Spring代理调用，确保事务注解生效
            SpringUtils.getAopProxy(this).handleProcessingFailure(gifMessage);

            throw new RuntimeException("保存GIF文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建GIF对象
     */
    private Gif buildGifFromMessage(GifMessage gifMessage) {
        Gif gif = new Gif();
        gif.setUserId(gifMessage.getUserId());
        gif.setTitle(StringUtils.hasText(gifMessage.getTitle()) ? gifMessage.getTitle() : gifMessage.getFileUrl());
        gif.setDescription(gifMessage.getDescription());
        gif.setFileUrl(gifMessage.getFileUrl());
        gif.setFileSize(gifMessage.getFileSize());
        gif.setStatus((byte) 1); // 默认状态为正常 -- 后续会改为审核 0
        gif.setViewCount(0);
        gif.setLikeCount(0);
        gif.setDownloadCount(0);
        return gif;
    }

    /**
     * 保存GIF标签信息
     */
    protected void saveGifTags(Long gifId, List<String> tags) {
        for (String tagName : tags) {
            // 1. 查找或创建Tag
            Tag tag = findOrCreateTag(tagName);

            // 如果tag处理失败，则跳过该tag
            if (tag == null) continue;
            
            // 2. 创建GifTag关联
            GifTag gifTag = new GifTag()
                .setGifId(gifId)
                .setTagId(tag.getId());

            if (!gifTagService.save(gifTag)) {
                throw new RuntimeException("保存GIF标签关联失败");
            }
        }
        log.info("保存GIF标签成功，GIF ID: {}, 标签数量: {}", gifId, tags.size());
    }

    /**
     * 查找或创建标签
     * 使用数据库的INSERT ... ON DUPLICATE KEY UPDATE解决并发问题
     */
    private Tag findOrCreateTag(String tagName) {
        Tag tag = new Tag().setName(tagName).setUseCount(1);
        
        // insert...on duplicate key update
        int result = tagMapper.insertOrUpdateTag(tag);
        
        if (result > 0) {
            // 操作成功，重新查询获取最新数据（包含正确的use_count和时间戳）
            Tag updatedTag = tagMapper.selectByName(tagName);
            if (updatedTag != null) {
                return updatedTag;
            } else {
                log.error("操作成功，但是查询标签失败 {}", tagName);
                return null; // 影响不是很大直接返回null 不处理这个标签
            }
        } else {
            log.warn("创建或修改标签失败: {}", tagName);
            return null; // 影响不是很大直接返回null 不处理这个标签
        }
    }

    /**
     * 事务提交后执行的回调
     */
    private void registerAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * 处理处理失败的情况
     * 使用独立事务，确保失败记录不会被主事务回滚影响
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handleProcessingFailure(GifMessage gifMessage) {
        if (gifMessage != null) {
            // 保存需要删除的文件到删除表中
            try {
                if (cacheService.setIfAbsent(HANDLE_GIF_MSG_FAIL + gifMessage.getFileUrl(), "1", 1L, TimeUnit.MINUTES)) {
                    gifDeleteService.save(new GifDelete()
                            .setFileUrl(gifMessage.getFileUrl())
                            .setCreatedAt(LocalDateTime.now()));
                    log.info("已记录需要删除的文件: {}", gifMessage.getFileUrl());
                }
            } catch (Exception deleteException) {
                log.error("保存删除记录失败: {}", deleteException.getMessage(), deleteException);
                throw deleteException; // 重新抛出异常，让独立事务回滚
            }
        }
    }

    /**
     * @param userId 用户ID
     * 增加GIF总数（+1）
     */
    private void incrementTotalGifCount(Long userId) {
        try {
            cacheService.increment(TOTAL_GIF_COUNT_KEY, 1);
            cacheService.increment(TOTAL_GIF_COUNT_KEY + ":" + userId, 1, 60, TimeUnit.MINUTES);
            log.info("GIF总数+1");
        } catch (Exception e) {
            log.error("增加GIF总数失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public String getQueueUrl() {
        return queueUrl;
    }
}
