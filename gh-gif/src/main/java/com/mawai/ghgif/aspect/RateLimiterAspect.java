package com.mawai.ghgif.aspect;

import com.mawai.ghgif.annotation.RateLimiter;
import com.mawai.ghgif.constant.RateLimiterType;
import com.mawai.ghgif.config.ThreadPoolConfig;
import com.mawai.ghgif.exception.RateLimitException;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * 限流切面
 * 基于令牌桶算法的分布式限流拦截器
 * 
 * @author mawai
 */
@Aspect
@Component
@Slf4j
public class RateLimiterAspect {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> rateLimiterScript;

    public RateLimiterAspect(StringRedisTemplate stringRedisTemplate) throws Exception {
        this.stringRedisTemplate = stringRedisTemplate;
        
        // 加载Lua脚本
        this.rateLimiterScript = new DefaultRedisScript<>();
        this.rateLimiterScript.setResultType(Long.class);
        String scriptText = StreamUtils.copyToString(
                new ClassPathResource("lua/token_bucket.lua").getInputStream(), 
                StandardCharsets.UTF_8);
        this.rateLimiterScript.setScriptText(scriptText);
        
        log.info("RateLimiterAspect initialized with token bucket lua script");
    }

    /**
     * 拦截带有@RateLimiter注解的方法
     *
     * @param joinPoint 切入点
     * @param rateLimiter 限流注解
     * @return 方法执行结果
     * @throws Throwable 异常
     */
    @Around("@annotation(rateLimiter)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimiter rateLimiter) throws Throwable {
        try {
            // 获取限流key
            String key = getLimiterKey(rateLimiter.type());
            double permitsPerSecond = rateLimiter.permitsPerSecond();
            int bucketCapacity = rateLimiter.bucketCapacity();
            long now = System.currentTimeMillis(); // 使用毫秒时间戳
            
            // 执行Lua脚本进行限流判断
            Long result = stringRedisTemplate.execute(
                    rateLimiterScript,
                    Collections.singletonList(key),
                    String.valueOf(permitsPerSecond),
                    String.valueOf(bucketCapacity),
                    String.valueOf(now),
                    "1"  // 请求1个令牌
            );

            if (result == 1) {
                // 限流通过，执行目标方法
                return joinPoint.proceed();
            } else {
                // 限流被拒绝
                log.warn("Rate limit exceeded for key: {}, method: {}", 
                        key, joinPoint.getSignature().toShortString());
                throw new RateLimitException(rateLimiter.message());
            }
        } catch (RateLimitException e) {
            // 重新抛出限流异常
            throw e;
        } catch (Exception e) {
            log.error("Rate limiter execution failed", e);
            // 发生异常时，为了安全起见，拒绝请求
            throw new RateLimitException("系统异常，请稍后再试");
        }
    }

    /**
     * 获取限流key
     * @param type 限流器类型
     *
     * @return 限流器key
     */
    private static String getLimiterKey(RateLimiterType type) {
        String loginId = null;
        // 尝试从当前线程获取登录id
        try {
            if (StpUtil.isLogin()) loginId = StpUtil.getLoginIdAsString();
        } catch (Exception e) {
            // 如果异常说明是新线程上传，则使用ThreadLocal获取登录id
            loginId = ThreadPoolConfig.CustomTaskDecorator.getAsyncLoginId();
        }

        if (loginId == null || loginId.isBlank()) {
            throw new RateLimitException("用户登录状态异常，无法进行限流");
        }

        return "limiter:" + type.name() + ":" + loginId;
    }
}
