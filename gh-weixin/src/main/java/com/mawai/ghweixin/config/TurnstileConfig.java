package com.mawai.ghweixin.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Getter
@Configuration
public class TurnstileConfig extends BaseRestTemplateConfig {

    @Value("${turnstile.secret-key}")
    private String secretKey;

    @Value("${turnstile.enabled:true}")
    private boolean enabled;

    @Value("${turnstile.connect-timeout:3000}")
    private int connectTimeout;

    @Value("${turnstile.read-timeout:5000}")
    private int readTimeout;

    @Bean(name = "turnstileRestTemplate")
    public RestTemplate turnstileRestTemplate() {
        return createRestTemplate(connectTimeout, readTimeout);
    }
}
