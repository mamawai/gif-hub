package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghaws.constant.MessageType;
import com.mawai.ghaws.service.MessageService;
import com.mawai.ghaws.sqs.MessageConsumer;
import com.mawai.ghaws.sqs.idempotent.IdempotentHandler;
import com.mawai.ghaws.sqs.idempotent.IdempotentResult;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.GiphyMessage;
import com.mawai.ghgif.dto.GiphyDTO;
import com.mawai.ghgif.service.GifProcessService;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.service.GifService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.function.Consumer;

/**
 * Giphy GIF 消息消费者
 * 处理从 Giphy 引入的 GIF，插入数据库并绑定到用户的默认喜欢分类
 *
 * @author mawai
 * @since 2025-11-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GiphyMessageConsumer implements MessageConsumer {

    private final GifService gifService;
    private final GifProcessService gifProcessService;
    private final MessageService messageService;
    private final CacheService cacheService;
    private final IdempotentHandler idempotentHandler;

    private static final String CONSUMER_TYPE = "giphy";
    private static final String TOTAL_GIF_COUNT_KEY = "gif:total:count";

    @Value("${aws.sqs.base-queue-url}")
    private String queueUrl;

    @Override
    public Consumer<Message> handleMessage() {
        return message -> SpringUtils.getAopProxy(this).handle(message);
    }

    public void handle(Message message) {
        String body = message.body();
        String messageId = message.messageId();
        log.info("GiphyMessageConsumer: 处理Giphy消息: messageId={}, body={}", messageId, body);

        if (body.isBlank()) {
            log.warn("接收到空消息，忽略处理");
            return;
        }

        // 解析消息
        GiphyMessage giphyMessage = JSONUtil.toBean(body, GiphyMessage.class);

        // 使用幂等性处理器执行业务逻辑（带事务）
        IdempotentResult result = idempotentHandler.executeWithTransaction(CONSUMER_TYPE, messageId, () -> {
            try {
                GiphyDTO giphyDTO = giphyMessage.getGiphyDTO();
                Long userId = giphyMessage.getUserId();
                Long categoryId = giphyMessage.getCategoryId();

                // 1. 获取或插入 GIF
                Long gifId = getOrInsertGif(giphyDTO);

                // 2. 执行 toggleGifLike（绑定到用户的默认喜欢分类）
                gifProcessService.toggleGifLike(
                        String.valueOf(gifId),
                        categoryId,
                        userId,
                        true
                );

                log.info("Giphy GIF 处理成功: gifId={}, userId={}, categoryId={}", gifId, userId, categoryId);

                // 事务提交后删除 SQS 消息
                registerAfterCommit(() -> {
                    try {
                        messageService.deleteMessage(message.receiptHandle(), queueUrl);
                        log.info("SQS消息删除成功，messageId: {}", messageId);
                    } catch (Exception e) {
                        log.error("删除SQS消息失败，messageId: {}", messageId, e);
                    }
                });

                return true;

            } catch (Exception e) {
                log.error("处理Giphy消息业务逻辑失败: messageId={}", messageId, e);
                throw new RuntimeException("处理Giphy消息失败: " + e.getMessage(), e);
            }
        });

        // 处理幂等性结果
        handleIdempotentResult(result, message);
    }

    /**
     * 获取或插入 GIF
     * 如果 GIF 已存在则返回已有的 ID，否则插入新 GIF
     */
    private Long getOrInsertGif(GiphyDTO giphyDTO) {
        String giphyId = giphyDTO.getGiphyId();

        // 查询是否已存在
        Gif existingGif = gifService.lambdaQuery()
                .eq(Gif::getGiphyId, giphyId)
                .one();

        if (existingGif != null) {
            log.info("GIF 已存在: giphyId={}, gifId={}", giphyId, existingGif.getId());
            return existingGif.getId();
        }

        // 不存在则插入
        Gif gif = new Gif();
        gif.setUserId(0L); // 硬编码用户ID，表示作者为 giphy
        gif.setGiphyId(giphyId);
        gif.setHeight(giphyDTO.getHeight());
        gif.setWidth(giphyDTO.getWidth());
        gif.setTitle(giphyDTO.getTitle());
        gif.setSource(giphyDTO.getSource());
        gif.setStatus((byte) 1);
        gif.setGiphyUsername(giphyDTO.getUsername());
        gif.setViewCount(0);
        gif.setLikeCount(0);
        gif.setDownloadCount(0);

        if (!gifService.insertOne(gif)) {
            throw new RuntimeException("插入 GIF 失败: giphyId=" + giphyId);
        }

        // insertOne 方法使用 useGeneratedKeys="true"，ID 会自动回填到 gif 对象
        // 更新总数缓存
        cacheService.increment(TOTAL_GIF_COUNT_KEY, 1);

        log.info("GIF 插入成功: giphyId={}, gifId={}", giphyId, gif.getId());
        return gif.getId();
    }

    /**
     * 处理幂等性结果
     */
    private void handleIdempotentResult(IdempotentResult result, Message message) {
        String messageId = message.messageId();

        switch (result.getStatus()) {
            case ALREADY_PROCESSED:
                messageService.deleteMessage(message.receiptHandle(), queueUrl);
                break;

            case PROCESSING:
                break;

            case SUCCESS:
                break;

            case FAILED:
            case EXCEPTION:
                long retryTimes = result.getRetryTimes();
                log.warn("Giphy消息处理失败，重试次数: {}", retryTimes);

                if (idempotentHandler.isExceedMaxRetry(retryTimes)) {
                    log.error("Giphy消息处理失败3次，messageId: {}", messageId);
                }

                if (result.shouldThrowException()) {
                    throw new RuntimeException("处理Giphy消息失败: " + result.getException().getMessage(), result.getException());
                }
                break;
        }
    }

    @Override
    public MessageType getType() {
        return MessageType.GIPHY_MESSAGE;
    }
}