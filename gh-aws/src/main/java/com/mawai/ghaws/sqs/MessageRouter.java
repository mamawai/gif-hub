package com.mawai.ghaws.sqs;

import com.mawai.ghaws.constant.MessageType;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.HashMap;
import java.util.Map;

/**
 * 消息路由器
 * 根据消息的messageAttribute类型路由到对应的处理器
 * 
 * @author mawai
 */
@Slf4j
public class MessageRouter {

    // 消息类型值到处理器的映射 -- 注册处理器和获取处理器是分开的，所以不需要使用ConcurrentHashMap
    private final Map<String, MessageConsumer> handlers = new HashMap<>();

    /**
     * 注册消息处理器
     * 
     * @param beanName Bean名称（如: gif, email）
     * @param messageConsumer 消息处理器
     */
    public void registerHandler(String beanName, MessageConsumer messageConsumer) {
        // 直接使用Bean名称作为消息类型
        handlers.put(beanName, messageConsumer);
        log.info("注册消息处理器: {} -> {}", beanName, messageConsumer.getClass().getSimpleName());
    }

    /**
     * 路由消息，返回对应的处理器
     * 
     * @param message SQS消息
     * @return 对应的消息处理器，如果没有找到返回null
     */
    public MessageConsumer routeMessage(Message message) {
        try {
            // 提取消息类型
            String messageType = extractMessageType(message);
            
            if (messageType == null) {
                log.warn("消息缺少类型信息，忽略处理: {}", message.messageId());
                return null;
            }

            // 查找对应的处理器
            MessageConsumer handler = handlers.get(messageType);
            if (handler != null) return handler;
            else {
                log.warn("未找到消息类型 {} 的处理器，忽略处理，消息ID: {}", messageType, message.messageId());
                return null;
            }

        } catch (Exception e) {
            log.error("路由消息时发生异常，消息ID: {}", message.messageId(), e);
            return null;
        }
    }

    /**
     * 从消息中提取消息类型
     * 
     * @param message SQS消息
     * @return 消息类型值，如果提取失败返回null
     */
    private String extractMessageType(Message message) {
        try {
            Map<String, MessageAttributeValue> attributes = message.messageAttributes();
            if (attributes == null || attributes.isEmpty()) {
                return null;
            }

            MessageAttributeValue typeAttribute = attributes.get(MessageType.TYPE.getValue());
            if (typeAttribute == null) {
                return null;
            }

            return typeAttribute.stringValue();

        } catch (Exception e) {
            log.error("提取消息类型时发生异常，消息ID: {}", message.messageId(), e);
            return null;
        }
    }

    /**
     * 获取已注册的处理器数量
     * 
     * @return 处理器数量
     */
    public int getHandlerCount() {
        return handlers.size();
    }

}
