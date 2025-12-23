package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghaws.constant.MessageType;
import com.mawai.ghaws.service.MessageService;
import com.mawai.ghaws.sqs.MessageConsumer;
import com.mawai.ghaws.sqs.idempotent.IdempotentHandler;
import com.mawai.ghaws.sqs.idempotent.IdempotentResult;
import com.mawai.ghcommon.service.UserNicknameCacheService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.NotificationMessage;
import com.mawai.ghgif.service.NotificationProcessService;
import com.mawai.ghgif.service.WebSocketNotificationService;
import com.mawai.ghgif.vo.NotificationVO;
import com.mawai.ghmbplus.model.Notification;
import com.mawai.ghmbplus.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * 通知消息消费者
 * 处理站内通知的落库和Redis计数
 *
 * @author mawai
 * @since 2025-12-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationMessageConsumer implements MessageConsumer {

    private final NotificationService notificationService;
    private final NotificationProcessService notificationProcessService;
    private final MessageService messageService;
    private final IdempotentHandler idempotentHandler;
    private final WebSocketNotificationService webSocketNotificationService;
    private final UserNicknameCacheService userNicknameCacheService;

    private static final String CONSUMER_TYPE = "notification";

    @Value("${aws.sqs.base-queue-url}")
    private String queueUrl;

    @Override
    public Consumer<Message> handleMessage() {
        return message -> SpringUtils.getAopProxy(this).handle(message);
    }

    public void handle(Message message) {
        String body = message.body();
        String messageId = message.messageId();
        log.info("NotificationMessageConsumer: 处理通知消息: messageId={}, body={}", messageId, body);

        // 判断是否poll的是空消息
        if (body.isBlank()) {
            log.warn("接收到空消息，忽略处理");
            return;
        }

        // 解析消息
        NotificationMessage notificationMessage = JSONUtil.toBean(body, NotificationMessage.class);

        // 使用幂等性处理器执行业务逻辑（带事务）
        IdempotentResult result = idempotentHandler.executeWithTransaction(CONSUMER_TYPE, messageId, () -> {
            try {
                // 构建通知对象
                Notification notification = buildNotification(notificationMessage);

                // 保存到数据库
                boolean saved = notificationService.save(notification);
                if (!saved) {
                    log.error("通知保存失败: recipientId={}, senderId={}, actionType={}",
                            notificationMessage.getRecipientId(),
                            notificationMessage.getSenderId(),
                            notificationMessage.getActionType());
                    return false;
                }

                // Redis未读计数+1
                notificationProcessService.incrementUnreadCount(notificationMessage.getRecipientId(), 1);

                log.info("通知处理成功: id={}, recipientId={}, senderId={}, actionType={}",
                        notification.getId(),
                        notificationMessage.getRecipientId(),
                        notificationMessage.getSenderId(),
                        notificationMessage.getActionType());

                // 事务提交后的回调
                registerAfterCommit(() -> {
                    try {
                        // 删除SQS消息
                        messageService.deleteMessage(message.receiptHandle(), queueUrl);
                        log.info("SQS消息删除成功，messageId: {}", messageId);

                        // 推送WebSocket通知（前端自行维护未读计数）
                        try {
                            NotificationVO notificationVO = buildNotificationVO(notification, notificationMessage);
                            webSocketNotificationService.sendNotificationToUser(
                                    notificationMessage.getRecipientId(),
                                    notificationVO
                            );
                        } catch (Exception wsEx) {
                            log.warn("WebSocket推送失败,不影响业务: {}", wsEx.getMessage());
                        }
                    } catch (Exception e) {
                        log.error("删除SQS消息失败: messageId={}, error={}", messageId, e.getMessage(), e);
                    }
                });

                return true;
            } catch (Exception e) {
                log.error("处理通知消息失败: messageId={}, error={}", messageId, e.getMessage(), e);
                return false;
            }
        });

        // 处理幂等性结果
        handleIdempotentResult(result, message);
    }

    /**
     * 处理幂等性结果
     */
    private void handleIdempotentResult(IdempotentResult result, Message message) {
        String messageId = message.messageId();

        switch (result.getStatus()) {
            case ALREADY_PROCESSED:
                // 消息已处理，删除 SQS 消息
                messageService.deleteMessage(message.receiptHandle(), queueUrl);
                log.info("通知消息已处理，已删除SQS消息: messageId={}", messageId);
                break;

            case PROCESSING:
                // 消息正在处理中，跳过
                log.info("通知消息正在处理中，跳过: messageId={}", messageId);
                break;

            case SUCCESS:
                // 处理成功（已在事务回调中删除消息）
                break;

            case FAILED:
            case EXCEPTION:
                // 处理失败或异常
                long retryTimes = result.getRetryTimes();
                log.warn("通知消息处理失败，重试次数: {}", retryTimes);

                // 重试3次后仍失败，记录错误日志
                if (idempotentHandler.isExceedMaxRetry(retryTimes)) {
                    log.error("通知消息处理失败3次，放弃处理，messageId: {}", messageId);
                }

                // 重新抛出异常，触发事务回滚
                if (result.shouldThrowException()) {
                    throw new RuntimeException("处理通知消息失败: " + result.getException().getMessage(), result.getException());
                }
                break;
        }
    }

    /**
     * 构建通知对象
     */
    private Notification buildNotification(NotificationMessage message) {
        Notification notification = new Notification();
        notification.setRecipientId(message.getRecipientId());
        notification.setSenderId(message.getSenderId());
        notification.setActionType(message.getActionType());
        notification.setTargetId(message.getTargetId());
        notification.setContentSnapshot(message.getContentSnapshot());
        notification.setIsRead(false);
        notification.setCreateTime(LocalDateTime.now());
        return notification;
    }

    /**
     * 构建通知VO用于WebSocket推送
     */
    private NotificationVO buildNotificationVO(Notification notification, NotificationMessage message) {
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setSenderId(message.getSenderId());

        // 获取发送者昵称
        String senderNickname = userNicknameCacheService.batchGetNicknames(
                List.of(message.getSenderId())
        ).get(message.getSenderId());
        vo.setSenderNickname(senderNickname);

        vo.setActionType(message.getActionType());
        vo.setTargetId(message.getTargetId());
        vo.setContentSnapshot(message.getContentSnapshot());
        vo.setIsRead(false);
        vo.setCreateTime(notification.getCreateTime());
        return vo;
    }

    @Override
    public MessageType getType() {
        return MessageType.NOTIFICATION_MESSAGE;
    }
}

