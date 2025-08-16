package com.mawai.ghgif.exception;

/**
 * 限流异常
 * 
 * @author mawai
 */
public class RateLimitException extends RuntimeException {
    
    public RateLimitException(String message) {
        super(message);
    }
    
    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}

