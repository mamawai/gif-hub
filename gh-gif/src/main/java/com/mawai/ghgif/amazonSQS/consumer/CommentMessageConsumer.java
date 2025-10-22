package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.message.CommentMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghmbplus.model.Comment;
import com.mawai.ghmbplus.service.CommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


/**
 * 评论消息消费者
 * 
 * @author mawai
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentMessageConsumer implements MessageConsumer {

    private final CommentService commentService;
    private final CacheService cacheService;
    private final MessageService messageService;
    
    private static final String COMMENT_MSG = "comment:msg:";
    
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
        log.info("CommentMessageConsumer: 处理评论消息: {}", body);

        // 判断是否poll的是空消息
        if (body.isBlank()) return;

        String key = null;
        try {
            CommentMessage commentMessage = JSONUtil.toBean(body, CommentMessage.class);
            Long userId = commentMessage.getUserId();
            key = COMMENT_MSG + messageId;
            
            // 幂等性校验
            Number value = cacheService.getNumber(key);
            if (value != null && value.longValue() == -1) {
                log.info("评论消息已处理成功，丢弃消息: {}", message);
                messageService.deleteMessage(message.receiptHandle(), queueUrl);
                return;
            }

            // 构建评论对象
            Comment comment = buildCommentFromMessage(commentMessage);
            
            // 如果是回复评论，需要查询父评论信息
            if (StrUtil.isNotBlank(commentMessage.getParentId())) {
                Long parentIdLong = Long.parseLong(commentMessage.getParentId());
                
                // 查询父评论（需要获取user_id用于冗余）
                LambdaQueryWrapper<Comment> queryWrapper = new LambdaQueryWrapper<>();
                queryWrapper.select(Comment::getId, Comment::getRootCommentId, Comment::getUserId, Comment::getStatus)
                           .eq(Comment::getId, parentIdLong);
                Comment parentComment = commentService.getOne(queryWrapper);
                
                if (parentComment == null || parentComment.getStatus() != 1) {
                    throw new RuntimeException("父评论不存在或已删除");
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
                throw new RuntimeException("保存评论失败");
            }
            log.info("保存评论成功，ID: {}", comment.getId());

            // 事务成功后的回调
            String finalKey = key;
            registerAfterCommit(() -> {
                try {
                    // 删除SQS消息
                    messageService.deleteMessage(message.receiptHandle(), queueUrl);
                    
                    // 处理成功标记
                    cacheService.set(finalKey, -1L, 6L, TimeUnit.HOURS);
                    log.info("SQS消息删除成功，messageId: {}", messageId);
                } catch (Exception e) {
                    log.error("删除SQS消息失败，messageId: {}", messageId, e);
                }
            });

        } catch (Exception e) {
            log.error("保存评论失败，Message信息: {}", message, e);
            
            // 记录重试次数
            if (key != null) {
                Long times = cacheService.increment(key, 1);
                log.warn("评论处理失败，重试次数: {}", times);
            }
            
            throw new RuntimeException("保存评论失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建评论对象
     */
    private Comment buildCommentFromMessage(CommentMessage commentMessage) {
        Comment comment = new Comment();
        comment.setUserId(commentMessage.getUserId());
        comment.setGifId(commentMessage.getGifId());
        comment.setContent(commentMessage.getContent());
        comment.setStatus((byte) 1); // 正常状态（未来可以加审核）
        comment.setLikeCount(0);
        return comment;
    }

    @Override
    public MessageType getType() {
        return MessageType.COMMENT_MESSAGE;
    }
}

