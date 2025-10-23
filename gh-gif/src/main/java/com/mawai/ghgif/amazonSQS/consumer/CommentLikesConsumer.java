package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.utils.SpringUtils;
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

import java.util.concurrent.TimeUnit;
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
    private final CacheService cacheService;
    private final CommentProcessService commentProcessService;
    
    private static final String COMMENT_LIKES_MSG_KEY = "commentlikes:msg:";

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

        // 幂等key
        String idempotencyKey = null;
        CommentLikesMessage clMessage = null;
        
        try {
            // 解析消息
            clMessage = JSONUtil.toBean(body, CommentLikesMessage.class);
            idempotencyKey = COMMENT_LIKES_MSG_KEY + messageId;
            Number value = cacheService.getNumber(idempotencyKey);
            
            // 幂等性校验
            if (value != null && value.longValue() == -1) {
                log.info("评论点赞消息已处理成功，丢弃消息: {}", messageId);
                messageService.deleteMessage(message.receiptHandle(), queueUrl);
                return;
            }
            
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
            String finalKey = idempotencyKey;
            registerAfterCommit(() -> {
                try {
                    messageService.deleteMessage(message.receiptHandle(), queueUrl);
                    // 处理成功加锁 / 这里 -1 区分重试和成功
                    cacheService.set(finalKey, -1, 6L, TimeUnit.HOURS);
                    log.info("SQS消息删除成功，messageId: {}", messageId);
                } catch (Exception e) {
                    log.error("删除SQS消息失败，messageId: {}", messageId, e);
                }
            });
            
        } catch (Exception e) {
            log.error("处理评论点赞消息失败，Message信息: {}", message, e);
            Long times = null;
            if (idempotencyKey != null) {
                times = cacheService.increment(idempotencyKey, 1);
            }
            if (clMessage != null && times != null && times == 3) {
                SpringUtils.getAopProxy(this).handleProcessingFailure(clMessage);
            }
            throw new RuntimeException("处理评论点赞消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理失败回滚redis
     * @param clMessage 用户评论点赞消息
     */
    private void handleProcessingFailure(CommentLikesMessage clMessage) {
        clMessage.getNewLikes().forEach(like ->
                commentProcessService.toggleCommentLike(
                        String.valueOf(like.getCommentId()), like.getUserId(), true
                )
        );
        clMessage.getDeleteLikes().forEach(dislike ->
                commentProcessService.toggleCommentLike(
                        String.valueOf(dislike.getCommentId()), dislike.getUserId(), false
                )
        );
    }

    /**
     * 获取消息类型
     */
    @Override
    public MessageType getType() {
        return MessageType.COMMENT_LIKES_MESSAGE;
    }

}
