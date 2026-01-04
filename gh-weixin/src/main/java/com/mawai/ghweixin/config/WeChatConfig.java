package com.mawai.ghweixin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class WeChatConfig extends BaseRestTemplateConfig {

    @Value("${wechat.connect-timeout:3000}")
    private int connectTimeout;

    @Value("${wechat.read-timeout:5000}")
    private int readTimeout;

    @Bean(name = "weChatRestTemplate")
    public RestTemplate weChatRestTemplate() {
        return createRestTemplate(connectTimeout, readTimeout);
    }
}