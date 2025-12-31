package com.mawai.ghweixin.exception;

import cn.dev33.satoken.exception.NotLoginException;
import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghweixin.utils.IpUtil;
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
     * 处理Sa-Token未登录异常
     */
    @ExceptionHandler(NotLoginException.class)
    public ApiResponse<Object> handleNotLoginException(NotLoginException e) {
        String message = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "未提供token";
            case NotLoginException.INVALID_TOKEN -> "token无效";
            case NotLoginException.TOKEN_TIMEOUT -> "token已过期，请重新登录";
            case NotLoginException.BE_REPLACED -> "token已被顶下线";
            case NotLoginException.KICK_OUT -> "token已被踢下线";
            case NotLoginException.TOKEN_FREEZE -> "token 已被冻结";
            case NotLoginException.NO_PREFIX_MESSAGE -> "未按照指定前缀提交 token";
            default -> "当前会话未登录";
        };
        log.warn("Not login exception: type={}, message={}", e.getType(), message);
        return ApiResponse.error(401, message);
    }

    /**
     * 处理非 Cloudflare 请求异常
     */
    @ExceptionHandler(IpUtil.InvalidRequestException.class)
    public ApiResponse<Object> handleInvalidRequestException(IpUtil.InvalidRequestException e) {
        log.warn("Invalid request exception: {}", e.getMessage());
        return ApiResponse.error(403, "访问被拒绝");
    }
}