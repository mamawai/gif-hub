package com.mawai.ghweixin.config;

import com.mawai.ghweixin.utils.IpUtil;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Cloudflare 配置类
 * 用于配置 Cloudflare IP 验证开关
 */
@Slf4j
@Getter
@Configuration
public class CloudflareConfig {

    @Value("${cloudflare.validation.enabled}")
    private boolean validationEnabled;

    /**
     * 初始化 IpUtil 配置
     */
    @PostConstruct
    public void init() {
        IpUtil.setCfValidationEnabled(validationEnabled);
        log.info("Cloudflare 配置初始化完成 - 验证开关: {}", validationEnabled);
    }
}
