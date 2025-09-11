package com.mawai.ghgif.amazonSQS.consumer;

import java.util.function.Consumer;
import software.amazon.awssdk.services.sqs.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

@Slf4j
//@Component
public class EmailMessageConsumer implements MessageConsumer{

    @Value("${aws.sqs.email-queue-url}")
    private String queueUrl;

    @Override
    public Consumer<Message> handleMessage() {
        return message -> log.info("EmailMessageConsumer: 处理邮件消息: {}", message);
    }

    @Override
    public String getQueueUrl() {
        return queueUrl;
    }
}
