package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghgif.amazonSQS.idempotent.IdempotentHandler;
import com.mawai.ghgif.amazonSQS.message.CommentLikesMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.service.CommentProcessService;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghmbplus.dao.CommentLikeMapper;
import com.mawai.ghmbplus.model.CommentLike;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 评论点赞消息消费者
 * 处理从SQS接收的评论点赞数据，异步同步到数据库
 *
 * @author mawai
 * @since 2025-10-17
 */
@Slf4j
@Component
public class CommentLikesConsumer extends AbstractBatchLikesConsumer<CommentLikesMessage, CommentLike> {

    private final CommentLikeMapper commentLikeMapper;
    private final CommentProcessService commentProcessService;

    private static final String CONSUMER_TYPE = "commentlikes";

    public CommentLikesConsumer(MessageService messageService, 
                                IdempotentHandler idempotentHandler,
                                CommentLikeMapper commentLikeMapper,
                                CommentProcessService commentProcessService) {
        super(messageService, idempotentHandler);
        this.commentLikeMapper = commentLikeMapper;
        this.commentProcessService = commentProcessService;
    }

    @Override
    protected String getConsumerType() {
        return CONSUMER_TYPE;
    }

    @Override
    protected CommentLikesMessage parseMessage(String body) {
        return JSONUtil.toBean(body, CommentLikesMessage.class);
    }

    @Override
    protected void collectLikes(CommentLikesMessage message, List<CommentLike> newLikes, List<CommentLike> deleteLikes) {
        if (message.getNewLikes() != null && !message.getNewLikes().isEmpty()) {
            newLikes.addAll(message.getNewLikes());
        }
        if (message.getDeleteLikes() != null && !message.getDeleteLikes().isEmpty()) {
            deleteLikes.addAll(message.getDeleteLikes());
        }
    }

    @Override
    protected int batchInsert(List<CommentLike> entities) {
        return commentLikeMapper.batchInsertIgnore(entities);
    }

    @Override
    protected int batchDelete(List<CommentLike> entities) {
        return commentLikeMapper.batchDelete(entities);
    }

    @Override
    protected void handleProcessingFailure(CommentLikesMessage message) {
        if (message.getNewLikes() != null && !message.getNewLikes().isEmpty()) {
            message.getNewLikes().forEach(like ->
                    commentProcessService.toggleCommentLike(
                            String.valueOf(like.getCommentId()), like.getUserId(), true
                    )
            );
        }

        if (message.getDeleteLikes() != null && !message.getDeleteLikes().isEmpty()) {
            message.getDeleteLikes().forEach(dislike ->
                    commentProcessService.toggleCommentLike(
                            String.valueOf(dislike.getCommentId()), dislike.getUserId(), false
                    )
            );
        }
    }

    @Override
    public MessageType getType() {
        return MessageType.COMMENT_LIKES_MESSAGE;
    }
}
