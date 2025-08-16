package com.mawai.ghgif.exception;

import com.mawai.ghcommon.domain.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 
 * @author mawai
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理限流异常
     */
    @ExceptionHandler(RateLimitException.class)
    public ApiResponse<Object> handleRateLimitException(RateLimitException e) {
        log.warn("Rate limit exception: {}", e.getMessage());
        return ApiResponse.error(429, e.getMessage());
    }
    
    /**
     * 处理其他运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public ApiResponse<Object> handleRuntimeException(RuntimeException e) {
        log.error("Runtime exception: ", e);
        return ApiResponse.error(500, "系统异常，请稍后再试");
    }
}

