package com.mawai.ghweixin.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * ProxyCheck.io API 配置类
 * 用于配置 IP 代理检测服务
 */
@Getter
@Configuration
public class ProxyCheckConfig {

    @Value("${proxycheck.api-keys}")
    private List<String> apiKeys;

    @Value("${proxycheck.enabled}")
    private boolean enabled;

    @Value("${proxycheck.connect.timeout}")
    private int connectTimeout;

    @Value("${proxycheck.read.timeout}")
    private int readTimeout;

    /**
     * 创建 RestTemplate Bean
     * 用于调用 ProxyCheck.io API
     */
    @Bean(name = "proxyCheckRestTemplate")
    public RestTemplate proxyCheckRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // 设置超时时间
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);  // 连接超时
        factory.setReadTimeout(readTimeout);        // 读取超时
        restTemplate.setRequestFactory(factory);

        return restTemplate;
    }
}
