package com.mawai.ghweixin.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * GetIPIntel API 配置类
 * 用于配置 IP 欺诈检测服务
 */
@Getter
@Configuration
public class GetIpIntelConfig {

    @Value("${getipintel.contact.email}")
    private String contactEmail;

    @Value("${getipintel.connect.timeout}")
    private int connectTimeout;

    @Value("${getipintel.read.timeout}")
    private int readTimeout;

    /**
     * 创建 RestTemplate Bean
     * 用于调用 GetIPIntel API
     */
    @Bean(name = "getIpIntelRestTemplate")
    public RestTemplate getIpIntelRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // 设置超时时间（防止 API 超时影响用户体验）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);  // 连接超时
        factory.setReadTimeout(readTimeout);        // 读取超时
        restTemplate.setRequestFactory(factory);

        return restTemplate;
    }

}
