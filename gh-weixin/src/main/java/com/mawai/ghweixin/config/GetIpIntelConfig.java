package com.mawai.ghweixin.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * GetIPIntel API 配置类
 * 用于配置 IP 欺诈检测服务
 */
@Getter
@Configuration
public class GetIpIntelConfig extends BaseRestTemplateConfig {

    @Value("${getipintel.contact.email}")
    private String contactEmail;

    @Value("${getipintel.connect.timeout}")
    private int connectTimeout;

    @Value("${getipintel.read.timeout}")
    private int readTimeout;

    @Bean(name = "getIpIntelRestTemplate")
    public RestTemplate getIpIntelRestTemplate() {
        return createRestTemplate(connectTimeout, readTimeout);
    }
}
