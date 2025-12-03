package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghaws.message.GifMessage;
import com.mawai.ghaws.sqs.MessageConsumer;
import com.mawai.ghaws.constant.MessageType;
import com.mawai.ghaws.service.MessageService;
import com.mawai.ghaws.sqs.idempotent.IdempotentHandler;
import com.mawai.ghaws.sqs.idempotent.IdempotentResult;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.utils.PinYinUtils;
import com.mawai.ghcommon.utils.SpringUtils;
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
import java.util.concurrent.atomic.AtomicReference;
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
    private final IdempotentHandler idempotentHandler;

    private static final String CONSUMER_TYPE = "gif";
    private static final String TOTAL_GIF_COUNT_KEY = "gif:total:count"; // GIF总数缓存键
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
        log.info("GifMessageConsumer: 处理GIF消息: messageId={}, body={}", messageId, body);

        // 判断是否poll的是空消息
        if (body.isBlank()) {
            log.warn("接收到空消息，忽略处理");
            return;
        }

        // 解析消息（提前解析，用于异常处理）
        GifMessage gifMessage = JSONUtil.toBean(body, GifMessage.class);

        AtomicReference<Long> gifId = new AtomicReference<>(-1L);
        // 使用幂等性处理器执行业务逻辑
        IdempotentResult result = idempotentHandler.execute(CONSUMER_TYPE, messageId, () -> {
            try {
                Long userId = gifMessage.getUserId();

                // 保存GIF信息
                Gif gif = buildGifFromMessage(gifMessage);
                if (!gifService.insertOne(gif)) {
                    log.error("保存GIF文件失败: gifMessage={}", gifMessage);
                    return false;
                }
                gifId.set(gif.getId());
                log.info("保存GIF文件成功，ID: {}", gif.getId());

                // 保存标签信息（如果存在）
                if (gifMessage.getTags() != null && !gifMessage.getTags().isBlank()) {
                    saveGifTags(gif.getId(), gifMessage.getTags());
                }

                // 事务成功后的回调
                registerAfterCommit(() -> {
                    try {
                        // 增加GIF总数
                        incrementTotalGifCount(userId);
                        // 删除SQS消息
                        messageService.deleteMessage(message.receiptHandle(), queueUrl);
                        log.info("SQS消息删除成功，messageId: {}", messageId);
                    } catch (Exception e) {
                        log.error("删除SQS消息失败，messageId: {}", messageId, e);
                    }
                });

                return true;

            } catch (Exception e) {
                log.error("处理GIF消息业务逻辑失败: messageId={}", messageId, e);
                throw new RuntimeException("处理GIF消息失败: " + e.getMessage(), e);
            }
        });

        // 处理幂等性结果
        handleIdempotentResult(result, message, gifMessage, gifId.get());
    }

    /**
     * 处理幂等性结果
     */
    private void handleIdempotentResult(IdempotentResult result, Message message, GifMessage gifMessage, Long gifId) {
        String messageId = message.messageId();

        switch (result.getStatus()) {
            case ALREADY_PROCESSED:
                // 消息已处理，删除 SQS 消息
                messageService.deleteMessage(message.receiptHandle(), queueUrl);
                break;

            case PROCESSING:
                // 消息正在处理中，跳过
                break;

            case SUCCESS:
                // 处理成功（已在事务回调中删除消息）
                break;

            case FAILED:
            case EXCEPTION:
                // 处理失败或异常
                long retryTimes = result.getRetryTimes();
                log.warn("GIF消息处理失败，重试次数: {}", retryTimes);

                // 重试3次后仍失败，记录删除信息（等待clearDeletedGifs删除r2文件）
                if (idempotentHandler.isExceedMaxRetry(retryTimes)) {
                    log.error("GIF消息处理失败3次，记录删除文件，messageId: {}", messageId);
                    SpringUtils.getAopProxy(this).handleProcessingFailure(gifMessage, gifId);
                }

                // 重新抛出异常，触发事务回滚
                if (result.shouldThrowException()) {
                    throw new RuntimeException("处理GIF消息失败: " + result.getException().getMessage(), result.getException());
                }
                break;
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
        gif.setGiphyId(gifMessage.getFileUrl());
        gif.setStatus((byte) 1); // 默认状态为正常 -- 后续会改为审核 0
        gif.setViewCount(0);
        gif.setLikeCount(0);
        gif.setDownloadCount(0);
        return gif;
    }

    /**
     * 保存GIF标签信息
     */
    private void saveGifTags(Long gifId, String tags) {
        List<String> tagNames = List.of(tags.split(","));
        for (String tagName : tagNames) {
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
        log.info("保存GIF标签成功，GIF ID: {}, 标签数量: {}", gifId, tagNames.size());
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
     * handle处理失败的情况
     * 使用独立事务，确保失败记录不会被主事务回滚影响
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void handleProcessingFailure(GifMessage gifMessage, Long gifId) {
        if (gifMessage != null) {
            // 保存需要删除的文件到删除表中
            try {
                if (cacheService.setIfAbsent(HANDLE_GIF_MSG_FAIL + gifMessage.getFileUrl(), "1", 1L, TimeUnit.MINUTES)) {
                    if (gifId < 0) {
                        log.error("文件本地保存失败无需删除，需要进行云端删除fileUrl:{}", gifMessage.getFileUrl());
                        return;
                    }
                    gifDeleteService.save(new GifDelete()
                            .setFileUrl(gifMessage.getFileUrl())
                            .setCreatedAt(LocalDateTime.now())
                            .setFileId(String.valueOf(gifId)));
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
