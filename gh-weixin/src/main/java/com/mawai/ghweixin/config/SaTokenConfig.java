package com.mawai.ghweixin.config;

import com.mawai.ghcommon.config.BaseSaTokenConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token配置类 - 微信模块
 * 继承通用配置，添加微信模块特有的排除路径
 */
@Configuration
public class SaTokenConfig extends BaseSaTokenConfig {

    public SaTokenConfig(StringRedisTemplate stringRedisTemplate) {
        super(stringRedisTemplate);
    }

    /**
     * 微信模块特有的排除路径
     */
    private final List<String> WECHAT_EXCLUDE_PATHS = Arrays.asList(
            "/wechat/login",
            "/wechat/public_key",
            "/user/web/code",
            "/user/web/register",
            "/user/web/login",
            "/user/reset-password/code",
            "/user/reset-password",
            "/user/ipPreCheck",
            "/user/oauth/linuxdo/callback",
            "/user/linuxdo/invite/apply",
            "/user/linuxdo/invite/status",
            "/user/linuxdo/invite/verify",
            "/linuxdo/**"
    );

    /**
     * 重写排除路径，合并通用路径和微信模块特有路径
     */
    @Override
    protected List<String> getExcludePaths() {
        List<String> allPaths = new ArrayList<>(getDefaultExcludePaths());
        allPaths.addAll(WECHAT_EXCLUDE_PATHS);
        return allPaths;
    }
} 