package com.mawai.ghcommon.config;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.listener.SaTokenListener;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Sa-Token 基础配置类
 * 提供通用的Sa-Token配置，各模块可以继承并自定义
 * 注意：此类为抽象类，不会被Spring直接实例化，必须由子类继承并添加@Configuration注解
 */
@RequiredArgsConstructor
public abstract class BaseSaTokenConfig implements WebMvcConfigurer {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Sa-Token 配置 Bean
     * @Primary注解覆盖 getSaTokenConfig() 这个Bean
     */
    @Bean
    @Primary
    public SaTokenConfig baseSaTokenConfig() {
        SaTokenConfig config = new SaTokenConfig();

        // token名称 (同时也是请求头中的key)
        config.setTokenName("satoken");

        // token有效期，单位秒，默认30天
        // config.setTimeout(604800);

        // token临时有效期 (指定时间内无操作就过期)，单位秒，7天
        config.setActiveTimeout(60 * 60 * 24 * 7);

        // 是否允许同一账号并发登录 (false表示只能在一端登录)
        config.setIsConcurrent(false);

        // 在多人登录同一账号时，是否共用一个token (false表示每次登录生成新token)
        config.setIsShare(true);

        // token风格
        config.setTokenStyle("uuid");

        // 是否输出操作日志
        config.setIsLog(true);

        // 是否从cookie中读取token
        config.setIsReadCookie(false);

        // 是否从请求头中读取token
        config.setIsReadHeader(true);

        return config;
    }

    @Bean
    public SaTokenListener saTokenListener() {
        return new SaTokenListener() {

            @Override
            public void doLogin(String loginType, Object loginId, String tokenValue, SaLoginParameter loginParameter) {

            }

            @Override
            public void doLogout(String loginType, Object loginId, String tokenValue) {

            }

            @Override
            public void doKickout(String loginType, Object loginId, String tokenValue) {

            }

            @Override
            public void doReplaced(String loginType, Object loginId, String tokenValue) {
                // 用户被顶下线时，直接从 Redis 删除旧 token 相关的所有 key
                try {
                    String prefix = "satoken:login:token:";
                    Set<String> keys = stringRedisTemplate.keys(prefix + tokenValue);
                    if (!keys.isEmpty()) {
                        stringRedisTemplate.delete(keys);
                    }
                } catch (Exception e) {
                    System.err.println("删除被顶下线的token失败: " + e.getMessage());
                }
            }

            @Override
            public void doDisable(String loginType, Object loginId, String service, int level, long disableTime) {

            }

            @Override
            public void doUntieDisable(String loginType, Object loginId, String service) {

            }

            @Override
            public void doOpenSafe(String loginType, String tokenValue, String service, long safeTime) {

            }

            @Override
            public void doCloseSafe(String loginType, String tokenValue, String service) {

            }

            @Override
            public void doCreateSession(String id) {

            }

            @Override
            public void doLogoutSession(String id) {

            }

            @Override
            public void doRenewTimeout(String tokenValue, Object loginId, long timeout) {

            }
        };
    }



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
