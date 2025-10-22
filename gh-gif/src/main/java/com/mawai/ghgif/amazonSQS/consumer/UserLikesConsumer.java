package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.UserLikesMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.service.GifProcessService;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghmbplus.model.UserLike;
import com.mawai.ghmbplus.service.UserLikeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserLikesConsumer implements MessageConsumer{

    private final UserLikeService userLikeService;
    private final MessageService messageService;
    private final CacheService cacheService;
    private final GifProcessService gifProcessService;
    private static final String USER_LIKES_MSG = "userlikes:msg:";
    
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
        log.info("UserLikesConsumer: 处理用户点赞消息: {}", body);

        // 判断是否poll的是空消息
        if (body.isBlank()) return;

        // 幂等key
        String idempotencyKey = null;
        UserLikesMessage ulMessage = null;
        try {
            ulMessage = JSONUtil.toBean(body, UserLikesMessage.class);
            idempotencyKey = USER_LIKES_MSG + messageId;
            Number value = cacheService.getNumber(idempotencyKey);

            // 幂等性校验
            if (value != null && value.longValue() == -1) {
                log.info("用户点赞消息已处理成功，丢弃消息: {}", message);
                messageService.deleteMessage(message.receiptHandle(), queueUrl);
                return;
            }
            
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
            String finalKey = idempotencyKey;
            registerAfterCommit(() -> {
                try {
                    messageService.deleteMessage(message.receiptHandle(), queueUrl);
                    // 处理成功加锁 / 这里 -1 区分重试和成功
                    cacheService.set(finalKey, -1L, 6L, TimeUnit.HOURS);
                    log.info("SQS消息删除成功，messageId: {}", messageId);
                } catch (Exception e) {
                    log.error("删除SQS消息失败，messageId: {}", messageId, e);
                }
            });

        } catch (Exception e) {
            log.error("处理用户点赞消息失败，Message信息: {}", message, e);
            Long times = null;
            if (idempotencyKey != null) {
                times = cacheService.increment(idempotencyKey, 1);
            }
            if (ulMessage != null && times != null && times == 3) {
                SpringUtils.getAopProxy(this).handleProcessingFailure(ulMessage);
            }
            throw new RuntimeException("处理用户点赞消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理失败回滚redis
     * @param ulMessage 用户点赞消息
     */
    private void handleProcessingFailure(UserLikesMessage ulMessage) {
        ulMessage.getNewLikes().forEach(like ->
                gifProcessService.toggleGifLike(
                        String.valueOf(like.getGifId()), like.getUserLikeCategoryId(), like.getUserId(), true
                )
        );
        ulMessage.getDeleteLikes().forEach(dislike ->
                gifProcessService.toggleGifLike(
                        String.valueOf(dislike.getGifId()), dislike.getUserLikeCategoryId(), dislike.getUserId(), false
                )
        );
    }

    @Override
    public MessageType getType() {
        return MessageType.USER_LIKES_MESSAGE;
    }
}
