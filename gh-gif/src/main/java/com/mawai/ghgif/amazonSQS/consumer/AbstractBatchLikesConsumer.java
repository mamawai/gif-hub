package com.mawai.ghgif.amazonSQS.consumer;

import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghgif.amazonSQS.batch.BatchMessageWrapper;
import com.mawai.ghgif.amazonSQS.batch.BatchProcessor;
import com.mawai.ghgif.amazonSQS.idempotent.IdempotentHandler;
import com.mawai.ghgif.amazonSQS.idempotent.IdempotentResult;
import com.mawai.ghgif.service.MessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.model.Message;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 批量点赞消息消费者抽象基类<br/>
 * 提取CommentLikesConsumer和UserLikesConsumer的公共逻辑
 *
 * @author mawai
 * @since 2025-11-20
 */
@Slf4j
public abstract class AbstractBatchLikesConsumer<T> implements MessageConsumer {

    protected final MessageService messageService;
    protected final IdempotentHandler idempotentHandler;

    @Value("${aws.sqs.base-queue-url}")
    protected String queueUrl;

    private BatchProcessor<T> batchProcessor;

    // 批量大小：20条
    private static final int BATCH_SIZE = 20;
    // 超时时间：2秒
    private static final long TIMEOUT_MILLIS = 2000;

    protected AbstractBatchLikesConsumer(MessageService messageService, IdempotentHandler idempotentHandler) {
        this.messageService = messageService;
        this.idempotentHandler = idempotentHandler;
    }

    @PostConstruct
    public void init() {
        this.batchProcessor = new BatchProcessor<>(BATCH_SIZE, TIMEOUT_MILLIS, this::processBatch);
    }

    @PreDestroy
    public void destroy() {
        if (batchProcessor != null) {
            batchProcessor.shutdown();
        }
    }

    /**
     * 处理消息入口
     */
    @Override
    public Consumer<Message> handleMessage() {
        return message -> SpringUtils.getAopProxy(this).handle(message);
    }

    /**
     * 处理单条消息
     */
    public void handle(Message message) {
        String body = message.body();
        String messageId = message.messageId();
        log.info("{}: 处理消息: messageId={}", getConsumerType(), messageId);

        if (body.isBlank()) {
            log.warn("接收到空消息，忽略处理");
            return;
        }

        // 解析消息
        T businessMessage = parseMessage(body);

        // 幂等性检查
        IdempotentResult result = idempotentHandler.execute(getConsumerType(), messageId, () -> {
            // 通过幂等性检查，加入批量处理队列
            batchProcessor.add(new BatchMessageWrapper<>(message, businessMessage));
            return true;
        });

        // 处理幂等性结果
        handleIdempotentResult(result, message, businessMessage);
    }

    /**
     * 批量处理消息
     */
    private void processBatch(List<BatchMessageWrapper<T>> batch) {
        try {
            // 尝试批量处理
            SpringUtils.getAopProxy(this).batchProcess(batch);
        } catch (Exception e) {
            log.error("批量处理失败，开始降级逐条处理", e);
            // 降级：逐条处理
            degradeToSingleProcess(batch);
        }
    }

    /**
     * 批量处理（在事务中执行）
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchProcess(List<BatchMessageWrapper<T>> batch) {
        log.info("开始批量处理: 消息数={}", batch.size());

        List<T> allNewLikes = new ArrayList<>();
        List<T> allDeleteLikes = new ArrayList<>();

        // 收集所有新增和删除的数据
        for (BatchMessageWrapper<T> wrapper : batch) {
            collectLikes(wrapper.getBusinessMessage(), allNewLikes, allDeleteLikes);
        }

        // 批量插入
        if (!allNewLikes.isEmpty()) {
            int inserted = batchInsert(allNewLikes);
            log.info("批量插入: 总数={}, 实际插入={}", allNewLikes.size(), inserted);
        }

        // 批量删除
        if (!allDeleteLikes.isEmpty()) {
            int deleted = batchDelete(allDeleteLikes);
            log.info("批量删除: 总数={}, 实际删除={}", allDeleteLikes.size(), deleted);
        }

        // 事务提交后删除所有SQS消息
        registerAfterCommit(() -> {
            for (BatchMessageWrapper<T> wrapper : batch) {
                try {
                    messageService.deleteMessage(wrapper.getSqsMessage().receiptHandle(), queueUrl);
                    log.debug("SQS消息删除成功: messageId={}", wrapper.getSqsMessage().messageId());
                } catch (Exception e) {
                    log.error("删除SQS消息失败: messageId={}", wrapper.getSqsMessage().messageId(), e);
                }
            }
        });
    }

    /**
     * 降级处理：逐条处理
     */
    private void degradeToSingleProcess(List<BatchMessageWrapper<T>> batch) {
        for (BatchMessageWrapper<T> wrapper : batch) {
            try {
                SpringUtils.getAopProxy(this).singleProcess(wrapper);
            } catch (Exception e) {
                log.error("单条处理失败: messageId={}", wrapper.getSqsMessage().messageId(), e);
                // 单条失败，回滚Redis
                handleProcessingFailure(wrapper.getBusinessMessage());
            }
        }
    }

    /**
     * 单条处理（在事务中执行）
     */
    @Transactional(rollbackFor = Exception.class)
    public void singleProcess(BatchMessageWrapper<T> wrapper) {
        T businessMessage = wrapper.getBusinessMessage();
        Message sqsMessage = wrapper.getSqsMessage();

        List<T> newLikes = new ArrayList<>();
        List<T> deleteLikes = new ArrayList<>();
        collectLikes(businessMessage, newLikes, deleteLikes);

        // 单条插入
        if (!newLikes.isEmpty()) {
            batchInsert(newLikes);
        }

        // 单条删除
        if (!deleteLikes.isEmpty()) {
            batchDelete(deleteLikes);
        }

        // 事务提交后删除SQS消息
        registerAfterCommit(() -> {
            try {
                messageService.deleteMessage(sqsMessage.receiptHandle(), queueUrl);
                log.info("单条处理成功，SQS消息已删除: messageId={}", sqsMessage.messageId());
            } catch (Exception e) {
                log.error("删除SQS消息失败: messageId={}", sqsMessage.messageId(), e);
            }
        });
    }

    /**
     * 处理幂等性结果
     */
    private void handleIdempotentResult(IdempotentResult result, Message message, T businessMessage) {
        String messageId = message.messageId();

        switch (result.getStatus()) {
            case ALREADY_PROCESSED:
                messageService.deleteMessage(message.receiptHandle(), queueUrl);
                break;

            case PROCESSING:
                break;

            case SUCCESS:
                break;

            case FAILED:
            case EXCEPTION:
                long retryTimes = result.getRetryTimes();
                log.warn("消息处理失败，重试次数: {}", retryTimes);

                if (idempotentHandler.isExceedMaxRetry(retryTimes)) {
                    log.error("消息处理失败3次，开始回滚 Redis，messageId: {}", messageId);
                    handleProcessingFailure(businessMessage);
                }

                if (result.shouldThrowException()) {
                    throw new RuntimeException("处理消息失败: " + result.getException().getMessage(), result.getException());
                }
                break;
        }
    }

    /**
     * 获取消费者类型
     */
    protected abstract String getConsumerType();

    /**
     * 解析消息
     */
    protected abstract T parseMessage(String body);

    /**
     * 收集新增和删除的点赞数据
     */
    protected abstract void collectLikes(T message, List<T> newLikes, List<T> deleteLikes);

    /**
     * 批量插入
     */
    protected abstract int batchInsert(List<T> likes);

    /**
     * 批量删除
     */
    protected abstract int batchDelete(List<T> likes);

    /**
     * 处理失败回滚Redis
     */
    protected abstract void handleProcessingFailure(T message);
}