package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.idempotent.IdempotentHandler;
import com.mawai.ghgif.amazonSQS.idempotent.IdempotentResult;
import com.mawai.ghgif.amazonSQS.message.CommentLikesMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.service.CommentProcessService;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghmbplus.dao.CommentLikeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.function.Consumer;

/**
 * 评论点赞消息消费者
 * 处理从SQS接收的评论点赞数据，异步同步到数据库
 *
 * @author mawai
 * @since 2025-10-17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentLikesConsumer implements MessageConsumer {

    private final CommentLikeMapper commentLikeMapper;
    private final MessageService messageService;
    private final CommentProcessService commentProcessService;
    private final IdempotentHandler idempotentHandler;

    private static final String CONSUMER_TYPE = "commentlikes";

    @Value("${aws.sqs.base-queue-url}")
    private String queueUrl;

    /**
     * 处理消息
     */
    @Override
    public Consumer<Message> handleMessage() {
        return message -> SpringUtils.getAopProxy(this).handle(message);
    }

    @Transactional(rollbackFor = Exception.class)
    public void handle(Message message) {
        String body = message.body();
        String messageId = message.messageId();
        log.info("CommentLikesConsumer: 处理评论点赞消息: messageId={}, body={}", messageId, body);

        // 判断是否poll的是空消息
        if (body.isBlank()) {
            log.warn("接收到空消息，忽略处理");
            return;
        }

        // 解析消息（提前解析，用于异常处理）
        CommentLikesMessage clMessage = JSONUtil.toBean(body, CommentLikesMessage.class);

        // 使用幂等性处理器执行业务逻辑
        IdempotentResult result = idempotentHandler.execute(CONSUMER_TYPE, messageId, () -> {
            try {
                // 处理新增的点赞（批量插入，使用INSERT IGNORE去重）
                if (clMessage.getNewLikes() != null && !clMessage.getNewLikes().isEmpty()) {
                    int inserted = commentLikeMapper.batchInsertIgnore(clMessage.getNewLikes());
                    log.info("用户{}批量保存了{}条评论点赞，实际插入{}条新记录",
                            clMessage.getUserId(),
                            clMessage.getNewLikes().size(),
                            inserted);
                }

                // 处理取消的点赞（批量删除）
                if (clMessage.getDeleteLikes() != null && !clMessage.getDeleteLikes().isEmpty()) {
                    int deleted = commentLikeMapper.batchDelete(clMessage.getDeleteLikes());
                    log.info("用户{}批量删除了{}条评论点赞，实际删除{}条记录",
                            clMessage.getUserId(),
                            clMessage.getDeleteLikes().size(),
                            deleted);
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
                log.error("处理评论点赞消息业务逻辑失败: messageId={}", messageId, e);
                throw new RuntimeException("处理评论点赞消息失败: " + e.getMessage(), e);
            }
        });

        // 处理幂等性结果
        handleIdempotentResult(result, message, clMessage);
    }

    /**
     * 处理幂等性结果
     */
    private void handleIdempotentResult(IdempotentResult result, Message message, CommentLikesMessage clMessage) {
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
                log.warn("评论点赞消息处理失败，重试次数: {}", retryTimes);

                // 重试3次后仍失败，回滚 Redis
                if (idempotentHandler.isExceedMaxRetry(retryTimes)) {
                    log.error("评论点赞消息处理失败3次，开始回滚 Redis，messageId: {}", messageId);
                    SpringUtils.getAopProxy(this).handleProcessingFailure(clMessage);
                }

                // 重新抛出异常，触发事务回滚
                if (result.shouldThrowException()) {
                    throw new RuntimeException("处理评论点赞消息失败: " + result.getException().getMessage(), result.getException());
                }
                break;
        }
    }

    /**
     * 处理失败回滚redis
     * @param clMessage 用户评论点赞消息
     */
    private void handleProcessingFailure(CommentLikesMessage clMessage) {
        if (clMessage.getNewLikes() != null && !clMessage.getNewLikes().isEmpty()) {
            clMessage.getNewLikes().forEach(like ->
                    commentProcessService.toggleCommentLike(
                            String.valueOf(like.getCommentId()), like.getUserId(), true
                    )
            );
        }

        if (clMessage.getDeleteLikes() != null && !clMessage.getDeleteLikes().isEmpty()) {
            clMessage.getDeleteLikes().forEach(dislike ->
                    commentProcessService.toggleCommentLike(
                            String.valueOf(dislike.getCommentId()), dislike.getUserId(), false
                    )
            );
        }
    }

    /**
     * 获取消息类型
     */
    @Override
    public MessageType getType() {
        return MessageType.COMMENT_LIKES_MESSAGE;
    }

}
