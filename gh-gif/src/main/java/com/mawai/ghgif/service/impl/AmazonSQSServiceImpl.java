package com.mawai.ghgif.service.impl;

// 移除虚拟队列相关导入
import com.mawai.ghgif.amazonSQS.AmazonSQSClientConfig;
import com.mawai.ghgif.amazonSQS.consumer.MessageConsumer;
import com.mawai.ghgif.amazonSQS.util.CustomSQSMessageConsumer;
import com.mawai.ghgif.service.AmazonSQSService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AmazonSQSServiceImpl implements AmazonSQSService, SmartLifecycle {

    private SqsClient sqsClient;
    private volatile boolean running = false;
    private final List<MessageConsumer> messageConsumers;
    private final Map<String, CustomSQSMessageConsumer> sqsMessageConsumers = new ConcurrentHashMap<>();
    
    @Value("${aws.sqs.base-queue-url}")
    private String baseQueueUrl;

    @PostConstruct
    public void init() {
        log.info("🔧 @PostConstruct: 初始化SQS客户端...");
        sqsClient = AmazonSQSClientConfig.getSqsClient();
        log.info("✅ @PostConstruct: SQS客户端初始化完成");
    }

    @PreDestroy
    public void destroy() {
        log.info("🧹 @PreDestroy: 清理SQS客户端资源...");
        
        if (sqsClient != null) {
            sqsClient.close();
        }
        log.info("✅ @PreDestroy: SQS客户端资源清理完成");
    }

    /**
     * @param message 发送消息
     * @param queueUrl 目标队列
     *
     * 发送消息，失败时最多重试3次
     */
    @Override
    public void send(String message, String queueUrl) {
        int maxRetries = 3;
        int attempts = 0;
        Exception lastException = null;
        
        while (attempts < maxRetries) {
            try {
                attempts++;
                log.info("尝试发送消息，第{}次尝试", attempts);
                
                // 使用AWS SDK v2的sendMessage方法
                SendMessageResponse response = sqsClient.sendMessage(
                        SendMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .messageBody(message)
                                .build()
                );
                
                // 检查响应是否成功
                if (response.messageId() != null && response.md5OfMessageBody() != null) {
                    log.info("消息发送成功，第{}次尝试，messageId: {}, md5OfMessageBody: {}", 
                            attempts, response.messageId(), response.md5OfMessageBody());
                    return; // 成功发送，直接返回
                } else {
                    // 响应无效，记录并准备重试
                    log.warn("消息发送响应无效，第{}次尝试失败，messageId: {}, md5OfMessageBody: {}", 
                            attempts, response.messageId(), response.md5OfMessageBody());
                }

            } catch (Exception e) {
                lastException = e;
                log.warn("消息发送异常，第{}次尝试失败: {}", attempts, e.getMessage());
            }
            
            // 如果还有重试机会，则等待后重试
            if (attempts < maxRetries) {
                try {
                    log.info("等待1秒后进行第{}次重试", attempts + 1);
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    // 恢复中断状态
                    Thread.currentThread().interrupt();
                    log.error("等待重试时被中断，停止发送消息");
                    throw new RuntimeException("发送消息时被中断", ie);
                }
            }
        }
        
        // 所有重试都失败了
        log.error("消息发送失败，已重试{}次，放弃发送", maxRetries);
        throw new RuntimeException("消息发送失败，已重试" + maxRetries + "次", lastException);
    }

    /**
     * 删除消息
     */
    @Override
    public void delete(String messageRecipe, String queueUrl) {
        DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(messageRecipe)
                .build();
        DeleteMessageResponse response = sqsClient.deleteMessage(deleteMessageRequest);
        log.info("删除消息成功, response: {}", response);
    }


    /**
     * SmartLifecycle - 启动SQS消费者
     */
    @Override
    public void start() {
        if (!running) {
            log.info("🚀 SmartLifecycle.start(): 启动{}个SQS消费者...", messageConsumers.size());
            
            for (MessageConsumer messageConsumer : messageConsumers) {
                String queueUrl = messageConsumer.getQueueUrl();
                String consumerName = messageConsumer.getClass().getSimpleName();

                log.info("启动消费者: {} - 队列: {}", consumerName, queueUrl);

                // 使用自定义的CustomSQSMessageConsumer
                CustomSQSMessageConsumer consumer = CustomSQSMessageConsumer.builder()
                        .sqsClient(sqsClient)
                        .queueUrl(queueUrl)
                        .messageConsumer(messageConsumer.handleMessage())
                        .maxWaitTimeSeconds(20)
                        .maxNumberOfMessages(10)
                        .pollingThreadCount(1)
                        .exceptionHandler(e -> log.error("消费者 {} 处理消息出错: {}", consumerName, e.getMessage(), e))
                        .shutdownHook(() -> log.info("消费者 {} 关闭钩子执行", consumerName))
                        .build();

                consumer.start();
                sqsMessageConsumers.put(consumerName, consumer);
                
                log.info("消费者 {} 启动成功", consumerName);
            }
            
            running = true;
            log.info("✅ SmartLifecycle.start(): {}个SQS消费者全部启动完成", sqsMessageConsumers.size());
        }
    }

    /**
     * SmartLifecycle - 停止SQS消费者
     */
    @Override
    public void stop() {
        if (running) {
            log.info("🛑 SmartLifecycle.stop(): 停止{}个SQS消费者...", sqsMessageConsumers.size());
            
            for (Map.Entry<String, CustomSQSMessageConsumer> entry : sqsMessageConsumers.entrySet()) {
                String consumerName = entry.getKey();
                CustomSQSMessageConsumer consumer = entry.getValue();
                
                try {
                    log.info("停止消费者: {}", consumerName);
                    consumer.terminate();
                    log.info("消费者 {} 已优雅关闭", consumerName);
                } catch (Exception e) {
                    log.error("关闭消费者 {} 时发生错误: {}", consumerName, e.getMessage(), e);
                }
            }
            
            sqsMessageConsumers.clear();
            running = false;
            log.info("✅ SmartLifecycle.stop(): 所有SQS消费者停止完成");
        }
    }

    /**
     * SmartLifecycle - 检查是否正在运行
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * SmartLifecycle - 获取启动阶段（数值越小越早启动）
     * 返回较高的数值，确保在其他组件之后启动
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
