package com.mawai.ghgif.service.impl;

import cn.hutool.core.util.StrUtil;
import com.mawai.ghgif.amazonSQS.AmazonSQSClientConfig;
import com.mawai.ghgif.amazonSQS.MessageRouter;
import com.mawai.ghgif.amazonSQS.consumer.MessageConsumer;
import com.mawai.ghgif.amazonSQS.util.CustomSQSMessageConsumer;
import com.mawai.ghgif.constant.MessageType;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AmazonSQSServiceImpl implements AmazonSQSService, SmartLifecycle {

    private SqsClient sqsClient;
    private volatile boolean running = false;
    private final List<MessageConsumer> messageConsumers;
    private CustomSQSMessageConsumer sqsMessageConsumer;
    
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
    public void send(String message, String queueUrl, MessageType messageType) {
        int maxRetries = 3;
        int attempts = 0;
        Exception lastException = null;
        
        while (attempts < maxRetries) {
            try {
                attempts++;
                log.info("尝试发送消息，第{}次尝试", attempts);

                // build messageAttribute -- 标记消息类型
                Map<String, MessageAttributeValue> messageAttribute = Map.of(
                        MessageType.TYPE.getValue(),
                        MessageAttributeValue.builder()
                                .dataType("String")
                                .stringValue(messageType.getValue())
                                .build()
                );

                // sendMessage
                SendMessageResponse response = sqsClient.sendMessage(
                        SendMessageRequest.builder()
                                .messageAttributes(messageAttribute)
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
            log.info("🚀 SmartLifecycle.start(): 启动统一SQS消费者，注册{}个消息处理器...", messageConsumers.size());
            
            // 创建消息路由器
            MessageRouter messageRouter = new MessageRouter();
            
            // 注册所有消息处理器到message router
            for (MessageConsumer messageConsumer : messageConsumers) {
                if (StrUtil.isNotBlank(messageConsumer.getType().getValue())) {
                    messageRouter.registerHandler(messageConsumer.getType().getValue(), messageConsumer);
                    log.info("注册消息处理器: {} ({})", messageConsumer.getType().getValue(), messageConsumer.getClass().getSimpleName());
                } else {
                    log.warn("未找到消费者Bean名称: {}", messageConsumer.getClass().getSimpleName());
                }
            }

            // 创建统一的CustomSQSMessageConsumer
            sqsMessageConsumer = CustomSQSMessageConsumer.builder()
                    .sqsClient(sqsClient)
                    .queueUrl(baseQueueUrl)
                    .messageRouter(messageRouter)
                    .maxWaitTimeSeconds(20)
                    .maxNumberOfMessages(10)
                    .pollingThreadCount(1)
                    .exceptionHandler(e -> log.error("统一消费者处理消息出错: {}", e.getMessage(), e))
                    .shutdownHook(() -> log.info("统一消费者关闭钩子执行"))
                    .build();

            sqsMessageConsumer.start();
            
            running = true;
            log.info("✅ SmartLifecycle.start(): 统一SQS消费者启动完成，已注册{}个处理器", messageRouter.getHandlerCount());
        }
    }

    /**
     * SmartLifecycle - 停止SQS消费者
     */
    @Override
    public void stop() {
        if (running && sqsMessageConsumer != null) {
            log.info("🛑 SmartLifecycle.stop(): 停止统一SQS消费者...");
            
            try {
                sqsMessageConsumer.terminate();
                log.info("统一SQS消费者已优雅关闭");
            } catch (Exception e) {
                log.error("关闭统一SQS消费者时发生错误: {}", e.getMessage(), e);
            }
            
            sqsMessageConsumer = null;
            running = false;
            log.info("✅ SmartLifecycle.stop(): 统一SQS消费者停止完成");
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
