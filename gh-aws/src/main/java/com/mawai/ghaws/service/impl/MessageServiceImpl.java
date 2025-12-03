package com.mawai.ghaws.service.impl;

import com.mawai.ghaws.constant.MessageType;
import com.mawai.ghaws.service.MessageService;
import com.mawai.ghaws.sqs.AmazonSQSClientConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private SqsClient sqsClient;
    private final AmazonSQSClientConfig amazonSQSClientConfig;

    @PostConstruct
    public void init() {
        sqsClient = amazonSQSClientConfig.getSqsClient();
    }

    @PreDestroy
    public void destroy() {
        sqsClient = null; // 清空引用即可
    }

    /**
     * @param message  发送消息
     * @param queueUrl 目标队列
     *
     *                 发送消息，失败时最多重试3次
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
                                .build());

                // sendMessage
                SendMessageResponse response = sqsClient.sendMessage(
                        SendMessageRequest.builder()
                                .messageAttributes(messageAttribute)
                                .queueUrl(queueUrl)
                                .messageBody(message)
                                .build());

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
     * 删除SQS消息
     * 
     * @param receiptHandle 消息的接收句柄，用于唯一标识要删除的消息
     * @param queueUrl      队列URL
     * @throws RuntimeException 当删除失败时抛出异常
     */
    @Override
    public void deleteMessage(String receiptHandle, String queueUrl) {
        try {
            DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build();

            sqsClient.deleteMessage(deleteMessageRequest);
            log.info("消息删除成功, receiptHandle: {}, queueUrl: {}", receiptHandle, queueUrl);

        } catch (Exception e) {
            log.error("消息删除失败, receiptHandle: {}, queueUrl: {}, 错误: {}",
                    receiptHandle, queueUrl, e.getMessage(), e);
            throw new RuntimeException("删除SQS消息失败: " + e.getMessage(), e);
        }
    }
}
