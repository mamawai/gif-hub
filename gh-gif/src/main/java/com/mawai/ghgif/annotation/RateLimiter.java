package com.mawai.ghgif.annotation;

import java.lang.annotation.*;
import com.mawai.ghgif.constant.RateLimiterType;

/**
 * 限流注解
 * 基于令牌桶算法的分布式限流
 * 
 * @author mawai
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {
    
    /**
     * 每秒生成的令牌数 -- 一分钟1个
     * @return 令牌生成速率
     */
    double permitsPerSecond() default (1.0 / 60);
    
    /**
     * 令牌桶容量
     * @return 桶容量
     */
    int bucketCapacity() default 1;
    
    /**
     * 限流失败时的提示信息
     * @return 错误信息
     */
    String message() default "请求过于频繁，请稍后再试";

    /**
     * 限流器类型（必填，为了区分不同的限流器类型）
     * @return 限流器类型
     */
    RateLimiterType type();
    
    /**
     * 业务key的参数名（可选，用于实现针对特定业务对象的限流）
     * 当指定此参数时，会从方法参数中提取对应的值作为限流key的一部分
     * 例如：指定为"fileId"时，限流key会变成 limiter:VIEW:userId:fileId
     * @return 参数名，默认为空字符串表示不使用业务key
     */
    String businessKeyParamName() default "";
}

