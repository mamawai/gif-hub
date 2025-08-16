package com.mawai.ghgif.util;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Slf4j
@Component
public class R2FileUtils implements InitializingBean {

    public static String END_POINT;
    public static String REGION;
    public static String BUCKET_NAME;
    public static String CDN_DOMAIN;
    public static String ACCESS_KEY;
    public static String SECRET_KEY;

    // 创建S3Client实例
    private static volatile S3Client s3Client;

    @PostConstruct
    public void init() {
        // 在@PostConstruct阶段，@Value注入的值已经可用，但静态变量尚未设置
        // 所以这里直接使用实例变量
        if (s3Client == null) {
            synchronized (R2FileUtils.class) {
                if (s3Client == null) {
                    // 创建S3Client实例，使用实例变量而非静态变量
                    s3Client = S3Client.builder()
                            .endpointOverride(URI.create(this.endpoint))
                            .credentialsProvider(() -> AwsBasicCredentials.create(
                                    this.accessKey,
                                    this.secretKey
                            ))
                            .region(Region.of(this.region))
                            .build();
                    log.info("S3Client created successfully.");
                }
            }
        }
    }

    @PreDestroy
    public void destroy() {
        // 关闭S3Client
        if (s3Client != null) {
            s3Client.close();
            log.info("S3Client closed successfully.");
        }
    }

    @Value("${cloudflare.aws.s3.endpoint}")
    private String endpoint;

    @Value("${cloudflare.aws.s3.region}")
    private String region;

    @Value("${cloudflare.aws.s3.bucket-name}")
    private String bucketName;

    @Value("${cloudflare.aws.s3.cdn-domain}")
    private String cdnDomain;

    @Value("${cloudflare.aws.s3.credentials.access-key}")
    private String accessKey;

    @Value("${cloudflare.aws.s3.credentials.secret-key}")
    private String secretKey;

    @Override
    public void afterPropertiesSet() throws Exception {
        END_POINT = this.endpoint;
        REGION = this.region;
        BUCKET_NAME = this.bucketName;
        CDN_DOMAIN = this.cdnDomain;
        ACCESS_KEY = this.accessKey;
        SECRET_KEY = this.secretKey;
    }

    public S3Client getS3Client() {
        return s3Client;
    }

}
