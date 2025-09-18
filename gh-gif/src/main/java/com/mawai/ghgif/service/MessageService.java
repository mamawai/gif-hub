package com.mawai.ghgif.service;

import com.mawai.ghgif.constant.MessageType;

/**
 * 消息服务接口
 */
public interface MessageService {

    /**
     * 发送消息到SQS队列
     *
     * @param message 要发送的消息内容
     * @param queueUrl 队列URL
     * @param messageType 消息类型
     * @throws RuntimeException 当发送失败时抛出异常
     */
    void send(String message, String queueUrl, MessageType messageType);
    
    /**
     * 删除SQS消息
     * 
     * @param receiptHandle 消息的接收句柄，用于唯一标识要删除的消息
     * @param queueUrl 队列URL
     * @throws RuntimeException 当删除失败时抛出异常
     */
    void deleteMessage(String receiptHandle, String queueUrl);
}
