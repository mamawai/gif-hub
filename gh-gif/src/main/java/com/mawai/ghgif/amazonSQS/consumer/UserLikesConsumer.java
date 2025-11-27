package com.mawai.ghgif.amazonSQS.consumer;

import cn.hutool.json.JSONUtil;
import com.mawai.ghgif.amazonSQS.idempotent.IdempotentHandler;
import com.mawai.ghgif.amazonSQS.message.UserLikesMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.service.GifProcessService;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghmbplus.model.UserLike;
import com.mawai.ghmbplus.service.UserLikeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户点赞消息消费者
 * 处理从SQS接收的用户点赞数据，异步同步到数据库
 *
 * @author mawai
 * @since 2025-10-17
 */
@Slf4j
@Component
public class UserLikesConsumer extends AbstractBatchLikesConsumer<UserLikesMessage, UserLike> {

    private final UserLikeService userLikeService;
    private final GifProcessService gifProcessService;

    private static final String CONSUMER_TYPE = "userlikes";

    public UserLikesConsumer(MessageService messageService,
                             IdempotentHandler idempotentHandler,
                             UserLikeService userLikeService,
                             GifProcessService gifProcessService) {
        super(messageService, idempotentHandler);
        this.userLikeService = userLikeService;
        this.gifProcessService = gifProcessService;
    }

    @Override
    protected String getConsumerType() {
        return CONSUMER_TYPE;
    }

    @Override
    protected UserLikesMessage parseMessage(String body) {
        return JSONUtil.toBean(body, UserLikesMessage.class);
    }

    @Override
    protected void collectLikes(UserLikesMessage message, List<UserLike> newLikes, List<UserLike> deleteLikes) {
        if (message.getNewLikes() != null && !message.getNewLikes().isEmpty()) {
            newLikes.addAll(message.getNewLikes());
        }
        if (message.getDeleteLikes() != null && !message.getDeleteLikes().isEmpty()) {
            deleteLikes.addAll(message.getDeleteLikes());
        }
    }

    @Override
    protected int batchInsert(List<UserLike> entities) {
        userLikeService.insertOrUpdateBatchByUniqueKey(entities);
        return entities.size();
    }

    @Override
    protected int batchDelete(List<UserLike> entities) {
        userLikeService.batchDelete(entities);
        return entities.size();
    }

    @Override
    protected void handleProcessingFailure(UserLikesMessage message) {
        if (message.getNewLikes() != null && !message.getNewLikes().isEmpty()) {
            message.getNewLikes().forEach(like ->
                    gifProcessService.toggleGifLike(
                            String.valueOf(like.getGifId()), like.getUserLikeCategoryId(), like.getUserId(), true
                    )
            );
        }

        if (message.getDeleteLikes() != null && !message.getDeleteLikes().isEmpty()) {
            message.getDeleteLikes().forEach(dislike ->
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
