package com.mawai.ghgif.amazonSQS.consumer;

import java.util.function.Consumer;

import com.mawai.ghaws.sqs.MessageConsumer;
import com.mawai.ghaws.constant.MessageType;
import software.amazon.awssdk.services.sqs.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EmailMessageConsumer implements MessageConsumer {

    @Override
    public Consumer<Message> handleMessage() {
        return message -> log.info("EmailMessageConsumer: 处理邮件消息: {}", message);
    }

    @Override
    public MessageType getType() {
        return MessageType.EMAIL_MESSAGE;
    }
}
