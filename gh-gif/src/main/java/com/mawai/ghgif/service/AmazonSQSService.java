package com.mawai.ghgif.service;

import cn.hutool.core.util.StrUtil;
import com.mawai.ghgif.amazonSQS.AmazonSQSClientConfig;
import com.mawai.ghgif.amazonSQS.MessageRouter;
import com.mawai.ghgif.amazonSQS.consumer.MessageConsumer;
import com.mawai.ghgif.amazonSQS.util.CustomSQSMessageConsumer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AmazonSQSService implements SmartLifecycle {

    private final AmazonSQSClientConfig amazonSQSClientConfig;
    private SqsClient sqsClient;
    private volatile boolean running = false; // 启动前若为true，则sqs关闭
    private final List<MessageConsumer> messageConsumers;
    private CustomSQSMessageConsumer sqsMessageConsumer;
    
    @Value("${aws.sqs.base-queue-url}")
    private String baseQueueUrl;

    @PostConstruct
    public void init() {
        sqsClient = amazonSQSClientConfig.getSqsClient();
    }

    @PreDestroy
    public void destroy() {
        sqsClient = null; // 清空引用即可
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
            registerConsumer(messageRouter);

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
     * 注册所有消息处理器到messageRouter
     */
    private void registerConsumer(MessageRouter messageRouter) {
        for (MessageConsumer messageConsumer : messageConsumers) {
            if (StrUtil.isNotBlank(messageConsumer.getType().getValue())) {
                messageRouter.registerHandler(messageConsumer.getType().getValue(), messageConsumer);
            } else {
                log.warn("未找到消费者Bean名称: {}", messageConsumer.getClass().getSimpleName());
            }
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
