package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.mawai.ghaws.sqs.MessageConsumer;
import com.mawai.ghaws.constant.MessageType;
import com.mawai.ghaws.service.MessageService;
import com.mawai.ghaws.sqs.idempotent.IdempotentHandler;
import com.mawai.ghaws.sqs.idempotent.IdempotentResult;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.CommentMessage;
import com.mawai.ghmbplus.model.Comment;
import com.mawai.ghmbplus.service.CommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.model.Message;

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

    @Override
    public MessageType getType() {
        return MessageType.COMMENT_MESSAGE;
    }
}

