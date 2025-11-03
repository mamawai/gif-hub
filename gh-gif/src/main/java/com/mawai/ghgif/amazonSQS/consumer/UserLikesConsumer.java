package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.idempotent.IdempotentHandler;
import com.mawai.ghgif.amazonSQS.idempotent.IdempotentResult;
import com.mawai.ghgif.amazonSQS.message.UserLikesMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.service.GifProcessService;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghmbplus.service.UserLikeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserLikesConsumer implements MessageConsumer {

    private final UserLikeService userLikeService;
    private final MessageService messageService;
    private final GifProcessService gifProcessService;
    private final IdempotentHandler idempotentHandler;

    private static final String CONSUMER_TYPE = "userlikes";

    @Value("${aws.sqs.base-queue-url}")
    private String queueUrl;

    @Override
    public Consumer<Message> handleMessage() {
        return message -> SpringUtils.getAopProxy(this).handle(message);
    }

    @Transactional(rollbackFor = Exception.class)
    public void handle(Message message) {
        String body = message.body();
        String messageId = message.messageId();
        log.info("UserLikesConsumer: 处理用户点赞消息: messageId={}, body={}", messageId, body);

        // 判断是否poll的是空消息
        if (body.isBlank()) {
            log.warn("接收到空消息，忽略处理");
            return;
        }

        // 解析消息（提前解析，用于异常处理）
        UserLikesMessage ulMessage = JSONUtil.toBean(body, UserLikesMessage.class);

        // 使用幂等性处理器执行业务逻辑
        IdempotentResult result = idempotentHandler.execute(CONSUMER_TYPE, messageId, () -> {
            try {
                // 处理新增的点赞
                if (ulMessage.getNewLikes() != null && !ulMessage.getNewLikes().isEmpty()) {
                    userLikeService.insertOrUpdateBatchByUniqueKey(ulMessage.getNewLikes());
                    log.info("用户{}批量保存/更新{}条喜欢记录",
                            ulMessage.getUserId(),
                            ulMessage.getNewLikes().size());
                }

                // 处理删除的点赞
                if (ulMessage.getDeleteLikes() != null && !ulMessage.getDeleteLikes().isEmpty()) {
                    // 批量删除
                    userLikeService.batchDelete(ulMessage.getDeleteLikes());
                    log.info("用户{}批量删除{}条喜欢记录",
                            ulMessage.getUserId(),
                            ulMessage.getDeleteLikes().size());
                }

                // 注册事务提交后的回调 - 确保只有在数据库事务成功提交后才删除SQS消息
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
                log.error("处理用户点赞消息业务逻辑失败: messageId={}", messageId, e);
                throw new RuntimeException("处理用户点赞消息失败: " + e.getMessage(), e);
            }
        });

        // 处理幂等性结果
        handleIdempotentResult(result, message, ulMessage);
    }

    /**
     * 处理幂等性结果
     */
    private void handleIdempotentResult(IdempotentResult result, Message message, UserLikesMessage ulMessage) {
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
                log.warn("用户点赞消息处理失败，重试次数: {}", retryTimes);

                // 重试3次后仍失败，回滚 Redis
                if (idempotentHandler.isExceedMaxRetry(retryTimes)) {
                    log.error("用户点赞消息处理失败3次，开始回滚 Redis，messageId: {}", messageId);
                    SpringUtils.getAopProxy(this).handleProcessingFailure(ulMessage);
                }

                // 重新抛出异常，触发事务回滚
                if (result.shouldThrowException()) {
                    throw new RuntimeException("处理用户点赞消息失败: " + result.getException().getMessage(), result.getException());
                }
                break;
        }
    }

    /**
     * 处理失败回滚redis
     * @param ulMessage 用户点赞消息
     */
    private void handleProcessingFailure(UserLikesMessage ulMessage) {
        if (ulMessage.getNewLikes() != null && !ulMessage.getNewLikes().isEmpty()) {
            ulMessage.getNewLikes().forEach(like ->
                    gifProcessService.toggleGifLike(
                            String.valueOf(like.getGifId()), like.getUserLikeCategoryId(), like.getUserId(), true
                    )
            );
        }

        if (ulMessage.getDeleteLikes() != null && !ulMessage.getDeleteLikes().isEmpty()) {
            ulMessage.getDeleteLikes().forEach(dislike ->
                    gifProcessService.toggleGifLike(
                            String.valueOf(dislike.getGifId()), dislike.getUserLikeCategoryId(), dislike.getUserId(), false
                    )
            );
        }
    }

    @Override
    public MessageType getType() {
        return MessageType.USER_LIKES_MESSAGE;
    }
}
