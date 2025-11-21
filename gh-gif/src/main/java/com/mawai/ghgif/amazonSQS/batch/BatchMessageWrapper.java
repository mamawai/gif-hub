package com.mawai.ghgif.amazonSQS.batch;

import lombok.AllArgsConstructor;
import lombok.Data;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * 批量消息包装类
 * 将SQS消息和业务消息包装在一起
 *
 * @author mawai
 * @since 2025-11-20
 */
@Data
@AllArgsConstructor
public class BatchMessageWrapper<T> {
    
    /**
     * SQS原始消息
     */
    private Message sqsMessage;
    
    /**
     * 业务消息对象
     */
    private T businessMessage;
}