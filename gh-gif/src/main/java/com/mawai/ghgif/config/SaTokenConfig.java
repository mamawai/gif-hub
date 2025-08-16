package com.mawai.ghgif.config;

import com.mawai.ghcommon.config.BaseSaTokenConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token配置类 - GIF模块
 * 继承通用配置，添加GIF模块特有的排除路径
 */
@Configuration
public class SaTokenConfig extends BaseSaTokenConfig {


    private final List<String> GIF_EXCLUDE_PATHS = Arrays.asList(
            "/gif/randomGifs",
            "/gif/randomGif",
            "/gif/user/**",
            "/gif/record-download"
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
