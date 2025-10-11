package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.GifMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghgif.util.PinYinUtils;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.GifDelete;
import com.mawai.ghmbplus.model.GifTag;
import com.mawai.ghmbplus.model.Tag;
import com.mawai.ghmbplus.service.GifDeleteService;
import com.mawai.ghmbplus.service.GifService;
import com.mawai.ghmbplus.service.GifTagService;
import com.mawai.ghmbplus.dao.TagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
    private final MessageService messageService;
    private final TagMapper tagMapper;
    private final GifTagService gifTagService;
    private final PinYinUtils pinYinUtils;
    private static final String TOTAL_GIF_COUNT_KEY = "gif:total:count"; // GIF总数缓存键
    private static final String GIF_MSG = "gif:msg:";
    private static final String HANDLE_GIF_MSG_FAIL = "gif:msg:fail:";
    private final static String TAG_KEY = "tag:";
    @Value("${aws.sqs.base-queue-url}")
    private String queueUrl;

    @Override
    public Consumer<Message> handleMessage() {
        return message -> SpringUtils.getAopProxy(this).handle(message); // 切面/事务生效
    }

    @Transactional(rollbackFor = Exception.class)
    public void handle(Message message) {
        String body = message.body();
        String messageId = message.messageId();
        log.info("GifMessageConsumer: 处理GIF消息: {}", body);

        // 判断是否poll的是空消息
        if (body.isBlank()) return;

        GifMessage gifMessage = null;
        String key = null;
        try {
            gifMessage = JSONUtil.toBean(body, GifMessage.class);
            Long userId = gifMessage.getUserId();
            key = GIF_MSG + messageId;
            Number value = cacheService.getNumber(key);
            // 幂等性校验
            if (value != null && value.longValue() == -1) {
                log.info("GIF消息已处理成功，丢弃消息: {}", message);
                messageService.deleteMessage(message.receiptHandle(), queueUrl);
                return;
            }

            // 保存GIF信息
            Gif gif = buildGifFromMessage(gifMessage);
            if (!gifService.insertOne(gif)) throw new RuntimeException("保存GIF文件失败");
            log.info("保存GIF文件成功，ID: {}", gif.getId());

            // 保存标签信息（如果存在）
            if (gifMessage.getTags() != null && !gifMessage.getTags().isEmpty()) {
                saveGifTags(gif.getId(), gifMessage.getTags());
            }

            // 可在这里模拟抛出异常 throw new RuntimeException("模拟异常");

            // 事务成功后的回调
            String finalKey = key;
            registerAfterCommit(() -> {
                try {
                    // 增加GIF总数
                    incrementTotalGifCount(userId);
                    // 删除SQS消息
                    messageService.deleteMessage(message.receiptHandle(), queueUrl);
                    // 处理成功加锁 / 这里 -1 区分重试和成功
                    cacheService.set(finalKey, -1L, 6L, TimeUnit.HOURS);
                    log.info("SQS消息删除成功，messageId: {}", messageId);
                } catch (Exception e) {
                    log.error("删除SQS消息失败，messageId: {}", messageId, e);
                }
            });

        } catch (Exception e) {
            log.error("保存GIF文件失败，Message信息: {}", message, e);
            Long times = null;
            if (key != null) {
                times = cacheService.increment(key, 1);
            }
            // 记录失败信息 -- 等待clearDeletedGifs删除r2文件
            // 通过代理调用，确保事务注解生效
            // 要等times为 3 才处理
            if (gifMessage != null && times != null && times == 3) {
                SpringUtils.getAopProxy(this).handleProcessingFailure(gifMessage);
            }
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
    // TODO 这里可以延后创建关联等审核通过后再创建tag再关联上gif
    private void saveGifTags(Long gifId, List<String> tags) {
        for (String tagName : tags) {
            // 1. 查找或创建Tag
            Long tagId = findOrCreateTag(tagName);

            // 如果tag处理失败，则跳过该tag
            if (tagId == null) continue;
            
            // 2. 创建GifTag关联
            GifTag gifTag = new GifTag()
                .setGifId(gifId)
                .setTagId(tagId);

            if (!gifTagService.save(gifTag)) {
                throw new RuntimeException("保存GIF标签关联失败");
            }
        }
        log.info("保存GIF标签成功，GIF ID: {}, 标签数量: {}", gifId, tags.size());
    }

    /**
     * 查找或创建标签
     * 使用数据库的INSERT ... ON DUPLICATE KEY UPDATE
     */
    private Long findOrCreateTag(String tagName) {
        Tag tag = new Tag().setName(tagName);
        
        // insert...on duplicate key update
        int result = tagMapper.insertOrUpdateTag(tag);
        
        if (result > 0) {
            // 存入缓存
            cacheService.zAddNX(TAG_KEY + pinYinUtils.getPinyinEngine().getFirstLetter(tagName.charAt(0)),
                    tagName, 0);

            // 操作成功，重新查询获取tagId
            Long tagId  = tagMapper.selectByName(tagName);
            if (tagId != null && tagId > 0) {
                return tagId;
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
    public MessageType getType () {
        return MessageType.GIF_MESSAGE;
    }
}
