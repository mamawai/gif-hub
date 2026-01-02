package com.mawai.ghcommon.utils;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;

/**
 * 异步上下文持有者
 * 用于在异步线程中传递登录信息
 */
@Slf4j
public class AsyncContextHolder {

    private static final ThreadLocal<String> ASYNC_LOGIN_ID = new ThreadLocal<>();

    /**
     * 获取异步线程中的登录ID
     */
    public static String getAsyncLoginId() {
        return ASYNC_LOGIN_ID.get();
    }

    /**
     * 设置异步线程中的登录ID
     */
    public static void setAsyncLoginId(String loginId) {
        ASYNC_LOGIN_ID.set(loginId);
    }

    /**
     * 清除异步线程中的登录ID
     */
    public static void removeAsyncLoginId() {
        ASYNC_LOGIN_ID.remove();
    }

    /**
     * 自定义任务装饰器，用于传递登录信息到异步线程
     */
    public static class CustomTaskDecorator implements TaskDecorator {
        @Override
        @NonNull
        public Runnable decorate(@NonNull Runnable runnable) {
            String loginId = null;
            try {
                if (StpUtil.isLogin()) {
                    loginId = StpUtil.getLoginIdAsString();
                }
            } catch (Exception e) {
                log.warn("CustomTaskDecorator: 获取主线程上下文失败", e);
            }
            final String finalLoginId = loginId;
            return () -> {
                try {
                    if (finalLoginId != null) setAsyncLoginId(finalLoginId);
                    runnable.run();
                } finally {
                    try {
                        removeAsyncLoginId();
                    } catch (Exception e) {
                        log.warn("CustomTaskDecorator: 清理子线程上下文信息失败", e);
                    }
                }
            };
        }
    }
}
