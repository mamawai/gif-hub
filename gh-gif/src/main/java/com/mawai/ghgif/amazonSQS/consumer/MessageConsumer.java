package com.mawai.ghgif.amazonSQS.consumer;

import java.util.function.Consumer;

import com.mawai.ghgif.constant.MessageType;
import software.amazon.awssdk.services.sqs.model.Message;

public interface MessageConsumer {

    /**
     * 消费消息
     * @return 消费消息的Consumer
     */
    Consumer<Message> handleMessage();

    /**
     * 获取队列Type
     * @return 队列Type
     */
    MessageType getType();

}
