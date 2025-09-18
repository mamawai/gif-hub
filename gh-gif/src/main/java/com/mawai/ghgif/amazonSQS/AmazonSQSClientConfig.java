package com.mawai.ghgif.amazonSQS;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import jakarta.annotation.PostConstruct;

@Component
@Slf4j
public class AmazonSQSClientConfig {

    @Value("${aws.sqs.region}")
    private String region;

    @Value("${aws.access-key-id}")
    private String accessKeyId;

    @Value("${aws.secret-access-key}")
    private String secretAccessKey;

    /**
     * -- GETTER --
     *  Get the SQS client
     */
    @Getter
    private static volatile SqsClient sqsClient;
    
    @PostConstruct
    public void init() {
        log.info("🔧 @PostConstruct: 初始化SQS客户端...");
        try {
            if (sqsClient == null) {
                synchronized (AmazonSQSClientConfig.class) {
                    if (sqsClient == null) {
                        sqsClient = SqsClient.builder()
                                .region(Region.of(this.region))
                                .credentialsProvider(StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                this.accessKeyId,
                                                this.secretAccessKey
                                        )
                                ))
                                .build();
                    }
                }
                log.info("✅ @PostConstruct: SQS客户端初始化完成");
            }
        } catch (Exception e) {
            log.error("Failed to initialize Amazon SQS Client", e);
            throw new RuntimeException("SQS initialization failed", e);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("🧹 @PreDestroy: 清理SQS客户端资源...");
        if (sqsClient != null) {
            sqsClient.close();
        }
        log.info("✅ @PreDestroy: SQS客户端资源清理完成");
    }
}
