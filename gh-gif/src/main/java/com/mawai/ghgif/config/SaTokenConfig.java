package com.mawai.ghgif.config;

import com.mawai.ghcommon.config.BaseSaTokenConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Sa-Token配置类 - GIF模块
 * 继承通用配置，添加GIF模块特有的排除路径
 */
@Configuration
public class SaTokenConfig extends BaseSaTokenConfig {

    public SaTokenConfig(StringRedisTemplate stringRedisTemplate) {
        super(stringRedisTemplate);
    }


    private final List<String> GIF_EXCLUDE_PATHS = Arrays.asList(
            "/gif/randomGifs",
            "/gif/randomGif",
            "/gif/user/**",
            "/gif/record-download",
            "/tag/**",
            "/api/giphy/**"
    );

    /**
     * 查询接口不需要拦截
     */
    @Override
    protected List<String> getExcludePaths() {
        List<String> allPaths = new ArrayList<>(getDefaultExcludePaths());
        allPaths.addAll(GIF_EXCLUDE_PATHS);
        return allPaths;
    }
}
