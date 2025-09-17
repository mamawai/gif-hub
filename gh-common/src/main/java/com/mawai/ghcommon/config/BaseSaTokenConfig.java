package com.mawai.ghcommon.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token 基础配置类
 * 提供通用的Sa-Token配置，各模块可以继承并自定义
 * 注意：此类为抽象类，不会被Spring直接实例化，必须由子类继承并添加@Configuration注解
 */
public abstract class BaseSaTokenConfig implements WebMvcConfigurer {

    /**
     * 默认不需要登录拦截的路径
     * 各模块可以通过重写getExcludePaths()方法来自定义
     */
    protected List<String> getDefaultExcludePaths() {
        return Arrays.asList(
                "/doc.html",
                "/webjars/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/swagger-resources/**",
                "/swagger-resources",
                "/v3/api-docs/**",
                "/v3/api-docs",
                "/favicon.ico",
                "/error",
                "/actuator/**"
        );
    }

    /**
     * 获取排除路径，子类可以重写此方法添加自定义路径
     */
    protected List<String> getExcludePaths() {
        return getDefaultExcludePaths();
    }

    /**
     * 是否启用Sa-Token拦截器，子类可以重写控制
     */
    protected boolean isInterceptorEnabled() {
        return true;
    }

    /**
     * 注册Sa-Token拦截器
     */
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        if (!isInterceptorEnabled()) {
            return;
        }

        // 注册Sa-Token拦截器，使用路由匹配方式更安全地处理认证
        registry.addInterceptor(new SaInterceptor(handle -> {
            SaRouter.match("/**")
                    .notMatch(getExcludePaths())
                    .check(r -> StpUtil.checkLogin());
        }))
        .addPathPatterns("/**");
    }
}
