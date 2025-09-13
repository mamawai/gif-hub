package com.mawai.ghgif.amazonSQS.util;

import com.mawai.ghgif.amazonSQS.MessageRouter;
import com.mawai.ghgif.amazonSQS.consumer.MessageConsumer;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 自定义SQS消息消费者
 * 用于持续轮询SQS队列并处理消息
 * 
 * @author mawai
 */
@Slf4j
public class CustomSQSMessageConsumer implements AutoCloseable {

    // 全局共享的缓存线程池
    private static final ExecutorService executor = Executors.newCachedThreadPool(
            r -> {
                Thread thread = new Thread(r, "CustomSQSMessageConsumer-" + System.currentTimeMillis());
                thread.setDaemon(true);
                return thread;
            });

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final MessageRouter messageRouter;
    private final Consumer<Exception> exceptionHandler;
    private final Runnable shutdownHook;
    
    // 配置参数
    private final int maxWaitTimeSeconds;
    private final int maxNumberOfMessages;
    private final int pollingThreadCount;
    
    // 状态控制
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final CountDownLatch terminated;
    

    /**
     * 构造器
     */
    private CustomSQSMessageConsumer(Builder builder) {
        this.sqsClient = Objects.requireNonNull(builder.sqsClient, "SqsClient不能为空");
        this.queueUrl = Objects.requireNonNull(builder.queueUrl, "队列URL不能为空");
        this.messageRouter = Objects.requireNonNull(builder.messageRouter, "消息路由器不能为空");
        this.exceptionHandler = builder.exceptionHandler != null ? builder.exceptionHandler : this::defaultExceptionHandler;
        this.shutdownHook = builder.shutdownHook != null ? builder.shutdownHook : () -> {};
        
        this.maxWaitTimeSeconds = builder.maxWaitTimeSeconds;
        this.maxNumberOfMessages = builder.maxNumberOfMessages;
        this.pollingThreadCount = builder.pollingThreadCount;
        
        this.terminated = new CountDownLatch(pollingThreadCount);
        
        log.info("创建CustomSQSMessageConsumer: 队列={}, 轮询线程数={}, 最大等待时间={}秒, 最大消息数={}", 
                queueUrl, pollingThreadCount, maxWaitTimeSeconds, maxNumberOfMessages);
    }

    /**
     * 启动消费者
     */
    public void start() {
        if (shuttingDown.get()) {
            throw new IllegalStateException("消费者已关闭，无法启动");
        }
        
        log.info("启动SQS消费者，队列: {}", queueUrl);
        for (int i = 0; i < pollingThreadCount; i++) {
            final int threadId = i;
            executor.execute(() -> pollMessages(threadId));
        }
    }


    /**
     * 轮询消息的主循环
     */
    @SuppressWarnings("BusyWait")
    private void pollMessages(int threadId) {
        log.debug("轮询线程-{} 开始运行", threadId);
        try {
            while (!Thread.interrupted() && !shuttingDown.get()) {
                try {
                    // 接收消息
                    ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .waitTimeSeconds(maxWaitTimeSeconds)
                            .maxNumberOfMessages(maxNumberOfMessages)
                            .messageAttributeNames("All")
                            .build();

                    List<Message> messages = sqsClient.receiveMessage(request).messages();

                    if (!messages.isEmpty()) {
                        log.info("轮询线程-{} 接收到 {} 条消息", threadId, messages.size());
                        // 并行处理消息
                        messages.parallelStream().forEach(this::handleMessage);
                    } else {
                        log.info("轮询线程-{} 没有收到消息", threadId);
                    }

                } catch (QueueDoesNotExistException e) {
                    log.warn("队列不存在: {}，等待1秒后重试", queueUrl);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                } catch (Exception e) {
                    log.error("轮询线程-{} 出现异常", threadId, e);
                    exceptionHandler.accept(e);
                }
            }
        } finally {
            terminated.countDown();
            log.debug("轮询线程-{} 已停止", threadId);
        }
    }

    /**
     * 处理单个消息
     */
    private void handleMessage(Message message) {
        if (shuttingDown.get()) {
            // 如果正在关闭，将消息可见性设置为0，让其他消费者可以立即处理
            try {
                changeMessageVisibilityZero(message);
                log.debug("消费者关闭中，重置消息可见性: {}", message.messageId());
            } catch (Exception e) {
                log.warn("重置消息可见性失败: {}", message.messageId(), e);
            }
            return;
        }

        try {
            // 使用路由器找到对应的处理器
            MessageConsumer messageConsumer = messageRouter.routeMessage(message);
            if (messageConsumer == null) {
                // 没有找到处理器，忽略消息（等待SQS重试机制处理）
                log.warn("未找到消息处理器，忽略消息: {}", message.messageId());
                return;
            }

            // 使用找到的处理器处理消息
            messageConsumer.handleMessage().accept(message);
            
            // 处理成功，删除消息
            deleteMessage(message);
            log.debug("消息处理成功并删除: {}", message.messageId());
            
        } catch (QueueDoesNotExistException e) {
            log.warn("队列不存在，忽略消息: {}", message.messageId());
        } catch (Exception e) {
            // 处理失败，记录异常
            String errorMessage = String.format("处理消息失败，ID: %s", message.messageId());
            RuntimeException processingException = new RuntimeException(errorMessage, e);
            exceptionHandler.accept(processingException);
            
            try {
                // 将消息可见性设置为0，让消息立即回到队列供重新处理 -- 注：最好结合死信队列来使用
                changeMessageVisibilityZero(message);
                log.warn("消息处理失败，已重置可见性: {}", message.messageId());
            } catch (Exception cmvException) {
                String cmvErrorMessage = String.format("重置消息可见性失败，ID: %s", message.messageId());
                RuntimeException visibilityException = new RuntimeException(cmvErrorMessage, cmvException);
                exceptionHandler.accept(visibilityException);
            }
        }
    }

    /**
     * 删除消息
     */
    private void deleteMessage(Message message) {
        DeleteMessageRequest request = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build();
        sqsClient.deleteMessage(request);
    }

    /**
     * 改变消息可见性
     */
    private void changeMessageVisibilityZero(Message message) {
        ChangeMessageVisibilityRequest request = ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .visibilityTimeout(0)
                .build();
        sqsClient.changeMessageVisibility(request);
    }

    /**
     * 默认异常处理器
     */
    private void defaultExceptionHandler(Exception e) {
        log.error("SQS消费者异常: {}", e.getMessage(), e);
    }

    /**
     * 优雅关闭
     */
    public void shutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            log.info("开始关闭SQS消费者: {}", queueUrl);
            runShutdownHook();
            // 注意：不关闭共享线程池，因为其他消费者可能还在使用
            log.info("SQS消费者已标记为关闭: {}", queueUrl);
        }
    }

    /**
     * 执行关闭钩子
     */
    private void runShutdownHook() {
        try {
            shutdownHook.run();
        } catch (Exception e) {
            log.error("执行关闭钩子时出错", e);
        }
    }

    /**
     * 检查是否已关闭
     */
    public boolean isShutdown() {
        return shuttingDown.get();
    }

    /**
     * 等待终止
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return terminated.await(timeout, unit);
    }

    /**
     * 立即终止
     */
    public void terminate() {
        shutdown();
        try {
            if (!awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("SQS消费者等待终止超时: {}", queueUrl);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Builder模式构建器
     */
    public static class Builder {
        private SqsClient sqsClient;
        private String queueUrl;
        private MessageRouter messageRouter;
        private Consumer<Exception> exceptionHandler;
        private Runnable shutdownHook;
        private int maxWaitTimeSeconds = 20;
        private int maxNumberOfMessages = 10;
        private int pollingThreadCount = 1;

        public Builder sqsClient(SqsClient sqsClient) {
            this.sqsClient = sqsClient;
            return this;
        }

        public Builder queueUrl(String queueUrl) {
            this.queueUrl = queueUrl;
            return this;
        }

        public Builder messageRouter(MessageRouter messageRouter) {
            this.messageRouter = messageRouter;
            return this;
        }

        public Builder exceptionHandler(Consumer<Exception> exceptionHandler) {
            this.exceptionHandler = exceptionHandler;
            return this;
        }

        public Builder shutdownHook(Runnable shutdownHook) {
            this.shutdownHook = shutdownHook;
            return this;
        }

        public Builder maxWaitTimeSeconds(int maxWaitTimeSeconds) {
            this.maxWaitTimeSeconds = Math.max(0, Math.min(20, maxWaitTimeSeconds)); // AWS限制最大20秒
            return this;
        }

        public Builder maxNumberOfMessages(int maxNumberOfMessages) {
            this.maxNumberOfMessages = Math.max(1, Math.min(10, maxNumberOfMessages)); // AWS限制最大10条
            return this;
        }

        public Builder pollingThreadCount(int pollingThreadCount) {
            this.pollingThreadCount = Math.max(1, pollingThreadCount);
            return this;
        }

        public CustomSQSMessageConsumer build() {
            return new CustomSQSMessageConsumer(this);
        }
    }

    /**
     * 创建Builder
     */
    public static Builder builder() {
        return new Builder();
    }
}
