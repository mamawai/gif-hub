package com.mawai.ghgif.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HttpClient 配置类
 */
@Configuration
public class HttpClientConfig {

    @Value("${http.client.connect-timeout:3000}")
    private int connectTimeoutMs;

    @Value("${http.client.request-timeout:3000}")
    private int requestTimeoutMs;

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public Duration getRequestTimeout() {
        return Duration.ofMillis(requestTimeoutMs);
    }
}