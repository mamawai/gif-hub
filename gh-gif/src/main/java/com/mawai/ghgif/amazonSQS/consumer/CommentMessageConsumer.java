package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.mawai.ghaws.sqs.MessageConsumer;
import com.mawai.ghaws.constant.MessageType;
import com.mawai.ghaws.service.MessageService;
import com.mawai.ghaws.sqs.idempotent.IdempotentHandler;
import com.mawai.ghaws.sqs.idempotent.IdempotentResult;
import com.mawai.ghcommon.service.UserNicknameCacheService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.CommentMessage;
import com.mawai.ghgif.service.NotificationProcessService;
import com.mawai.ghgif.service.WebSocketNotificationService;
import com.mawai.ghgif.vo.NotificationVO;
import com.mawai.ghmbplus.model.Comment;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.Notification;
import com.mawai.ghmbplus.service.CommentService;
import com.mawai.ghmbplus.service.GifService;
import com.mawai.ghmbplus.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * 评论消息消费者
 *
 * <p><b>缓存策略</b>：</p>
 * <ul>
 *   <li>新增评论时，只写数据库，<b>不修改 ZSet 缓存</b></li>
 *   <li>前端强制顺序分页，ZSet 会逐步累积数据</li>
 *   <li>查询时检测到 ZSet 数据不足，会自动查询数据库并追加</li>
 * </ul>
 *
 * @author mawai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentMessageConsumer implements MessageConsumer {

    private final CommentService commentService;
    private final MessageService messageService;
    private final IdempotentHandler idempotentHandler;
    private final GifService gifService;
    private final NotificationService notificationService;
    private final NotificationProcessService notificationProcessService;
    private final WebSocketNotificationService webSocketNotificationService;
    private final UserNicknameCacheService userNicknameCacheService;

    private static final String CONSUMER_TYPE = "comment";

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
        log.info("CommentMessageConsumer: 处理评论消息: messageId={}, body={}", messageId, body);

        // 判断是否poll的是空消息
        if (body.isBlank()) {
            log.warn("接收到空消息，忽略处理");
            return;
        }

        // 解析消息（提前解析，用于异常处理）
        CommentMessage commentMessage = JSONUtil.toBean(body, CommentMessage.class);

        // 使用幂等性处理器执行业务逻辑
        IdempotentResult result = idempotentHandler.execute(CONSUMER_TYPE, messageId, () -> {
            try {
                Long userId = commentMessage.getUserId();

                // 构建评论对象
                Comment comment = buildCommentFromMessage(commentMessage);

                // 如果是回复评论，需要查询父评论信息
                if (StrUtil.isNotBlank(commentMessage.getParentId())) {
                    Long parentIdLong = Long.parseLong(commentMessage.getParentId());

                    // 查询父评论 (需要获取user_id用于冗余)
                    Comment parentComment = commentService.lambdaQuery()
                        .select(Comment::getId, Comment::getRootCommentId, Comment::getUserId, Comment::getStatus)
                        .eq(Comment::getId, parentIdLong)
                        .one();

                    if (parentComment == null || parentComment.getStatus() != 1) {
                        log.error("父评论不存在或已删除: parentId={}", parentIdLong);
                        return false;
                    }

                    comment.setParentId(parentIdLong);
                    comment.setParentUserId(parentComment.getUserId());

                    // 如果父评论是根评论，则root_comment_id为父评论ID
                    // 如果父评论是子评论，则继承其root_comment_id
                    Long rootId = parentComment.getRootCommentId() != null
                            ? parentComment.getRootCommentId()
                            : parentComment.getId();
                    comment.setRootCommentId(rootId);

                    log.info("用户{}回复评论{}（被回复者: {}），根评论ID: {}",
                            userId, commentMessage.getParentId(), parentComment.getUserId(), rootId);
                } else {
                    // 根评论：parent_id和root_comment_id和parent_userId都为NULL
                    comment.setParentId(null).setRootCommentId(null).setParentUserId(null);
                    log.info("用户{}发表根评论，GIF ID: {}", userId, comment.getGifId());
                }

                // 保存到数据库
                if (!commentService.save(comment)) {
                    log.error("保存评论失败: comment={}", comment);
                    return false;
                }
                log.info("保存评论成功，ID: {}", comment.getId());

                // 事务成功后的回调
                registerAfterCommit(() -> {
                    try {
                        // 删除SQS消息
                        messageService.deleteMessage(message.receiptHandle(), queueUrl);
                        log.info("SQS消息删除成功，messageId: {}", messageId);

                        // 直接处理评论通知（落库 + WebSocket推送）
                        processCommentNotification(comment);
                    } catch (Exception e) {
                        log.error("删除SQS消息失败，messageId: {}", messageId, e);
                    }
                });

                return true;

            } catch (Exception e) {
                log.error("处理评论消息业务逻辑失败: messageId={}", messageId, e);
                throw new RuntimeException("处理评论消息失败: " + e.getMessage(), e);
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
                log.warn("评论消息处理失败，重试次数: {}", retryTimes);

                // 重试3次后仍失败，记录错误日志（评论失败无需回滚Redis）
                if (idempotentHandler.isExceedMaxRetry(retryTimes)) {
                    log.error("评论消息处理失败3次，放弃处理，messageId: {}", messageId);
                }

                // 重新抛出异常，触发事务回滚
                if (result.shouldThrowException()) {
                    throw new RuntimeException("处理评论消息失败: " + result.getException().getMessage(), result.getException());
                }
                break;
        }
    }

    /**
     * 构建评论对象
     */
    private Comment buildCommentFromMessage(CommentMessage commentMessage) {
        Comment comment = new Comment();
        comment.setId(commentMessage.getCommentId());  // ✅ 使用预生成的ID
        comment.setUserId(commentMessage.getUserId());
        comment.setGifId(commentMessage.getGifId());
        comment.setContent(commentMessage.getContent());
        if (StrUtil.isNotBlank(commentMessage.getParentId())) {
            comment.setParentId(Long.valueOf(commentMessage.getParentId()));
        }
        comment.setStatus((byte) 1); // 正常状态（未来可以加审核）
        comment.setLikeCount(0);
        return comment;
    }

    /**
     * 处理评论通知（落库 + WebSocket推送）
     *
     * <p>通知规则：</p>
     * <ul>
     *   <li>根评论（parentId为空）：通知GIF作者（userId=0的Giphy GIF不发通知），actionType=3</li>
     *   <li>回复评论（parentId不为空）：通知被回复的评论作者，actionType=4</li>
     *   <li>自我排斥：不给自己发通知</li>
     * </ul>
     */
    private void processCommentNotification(Comment comment) {
        try {
            Long recipientId;  // 接收通知的用户ID
            Short actionType;  // 动作类型
            Long targetId;  // 目标ID
            
            // 判断是根评论还是回复评论
            if (comment.getParentId() == null) {
                // 根评论：通知GIF作者
                Gif gif = gifService.getById(comment.getGifId());
                if (gif == null || gif.getUserId() == null) {
                    log.warn("GIF不存在或无作者,跳过通知: gifId={}", comment.getGifId());
                    return;
                }
                
                // Giphy的GIF（userId=0）不发通知
                if (gif.getUserId() == 0L) return;
                
                // 自我排斥:不给自己发通知
                if (gif.getUserId().equals(comment.getUserId())) return;
                
                recipientId = gif.getUserId();
                actionType = 3;  // 3-评论GIF
                targetId = comment.getGifId();
                
                log.info("根评论通知: 用户{}评论GIF{}, 通知GIF作者{}",
                        comment.getUserId(), comment.getGifId(), recipientId);
                
            } else {
                // 回复评论：通知被回复的评论作者
                Comment parentComment = commentService.getById(comment.getParentId());
                if (parentComment == null || parentComment.getUserId() == null) {
                    log.warn("父评论不存在或无作者,跳过通知: parentId={}", comment.getParentId());
                    return;
                }
                
                // 自我排斥:不给自己发通知
                if (parentComment.getUserId().equals(comment.getUserId())) return;
                
                recipientId = parentComment.getUserId();
                actionType = 4;  // 4-回复评论
                targetId = comment.getParentId();
                
                log.info("回复评论通知: 用户{}回复评论{}, 通知评论作者{}",
                        comment.getUserId(), comment.getParentId(), recipientId);
            }

            // 构建内容快照
            String contentSnapshot = comment.getContent();
            if (contentSnapshot != null && contentSnapshot.length() > 50) {
                contentSnapshot = contentSnapshot.substring(0, 50) + "...";
            }

            // 1. 构建通知对象并保存到数据库
            Notification notification = new Notification();
            notification.setRecipientId(recipientId);
            notification.setSenderId(comment.getUserId());
            notification.setActionType(actionType);
            notification.setTargetId(targetId);
            notification.setContentSnapshot(contentSnapshot);
            notification.setIsRead(false);
            notification.setCreateTime(LocalDateTime.now());

            boolean saved = notificationService.save(notification);
            if (!saved) {
                log.error("评论通知保存失败: recipientId={}, senderId={}, actionType={}",
                        recipientId, comment.getUserId(), actionType);
                return;
            }

            // 2. Redis未读计数+1
            notificationProcessService.incrementUnreadCount(recipientId, 1);

            log.info("评论通知保存成功: id={}, recipientId={}, senderId={}, actionType={}",
                    notification.getId(), recipientId, comment.getUserId(), actionType);

            // 3. 构建通知VO并推送WebSocket（前端自行维护未读计数）
            try {
                NotificationVO notificationVO = buildNotificationVO(notification, comment.getUserId());

                // 推送通知到WebSocket
                webSocketNotificationService.sendNotificationToUser(
                        recipientId,
                        notificationVO
                );

                log.info("评论通知WebSocket推送成功: recipientId={}, senderId={}, actionType={}",
                        recipientId, comment.getUserId(), actionType);
            } catch (Exception wsEx) {
                log.warn("评论通知WebSocket推送失败,不影响业务: {}", wsEx.getMessage());
            }

        } catch (Exception e) {
            // 通知处理失败不影响评论业务
            log.error("处理评论通知失败: commentId={}, error={}", comment.getId(), e.getMessage(), e);
        }
    }

    /**
     * 构建通知VO用于WebSocket推送
     */
    private NotificationVO buildNotificationVO(Notification notification, Long senderId) {
        NotificationVO vo = new NotificationVO();
        vo.setId(notification.getId());
        vo.setSenderId(senderId);

        // 获取发送者昵称
        vo.setSenderNickname(userNicknameCacheService
                .batchGetNicknames(List.of(senderId)).get(senderId));

        vo.setActionType(notification.getActionType());
        vo.setTargetId(notification.getTargetId());
        vo.setContentSnapshot(notification.getContentSnapshot());
        vo.setIsRead(false);
        vo.setCreateTime(notification.getCreateTime());
        return vo;
    }

    @Override
    public MessageType getType() {
        return MessageType.COMMENT_MESSAGE;
    }
}

