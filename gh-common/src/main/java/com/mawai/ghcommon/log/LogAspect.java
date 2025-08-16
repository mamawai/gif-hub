package com.mawai.ghcommon.log;

import cn.hutool.json.JSONUtil;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一日志处理切面
 */
@Aspect
@Component
@Order(1)
public class LogAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(LogAspect.class);

    private final ThreadLocal<Long> startTimeThreadLocal = new ThreadLocal<>();

    @SuppressWarnings("AopLanguageInspection")
    @Pointcut("execution(public * com.mawai..controller.*.*(..))")
    public void webLog() {
    }

    @Before("webLog()")
    public void doBefore(JoinPoint joinPoint) {
        // 记录开始时间
        startTimeThreadLocal.set(System.currentTimeMillis());

        // 获取请求信息
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            LOGGER.error("Failed to get request information");
            return;
        }
        Map<String, Object> webLog = getWebLog(joinPoint, attributes);

        // 使用JSONUtil格式化输出
        LOGGER.info(JSONUtil.toJsonPrettyStr(JSONUtil.parse(webLog)));
    }

    /**
     * 获取请求信息
     */
    private static Map<String, Object> getWebLog(JoinPoint joinPoint, ServletRequestAttributes attributes) {
        HttpServletRequest request = attributes.getRequest();

        // 构建日志信息Map
        Map<String, Object> webLog = new HashMap<>();
        webLog.put("type", "Request Start");
        webLog.put("url", request.getRequestURL().toString());
        webLog.put("method", request.getMethod());
        webLog.put("ip", request.getRemoteAddr());
        webLog.put("class", joinPoint.getSignature().getDeclaringTypeName());
        webLog.put("function", joinPoint.getSignature().getName());
        webLog.put("args", Arrays.toString(joinPoint.getArgs()));
        return webLog;
    }

    @AfterReturning(value = "webLog()", returning = "result")
    public void doAfterReturning(JoinPoint joinPoint, Object result) {
        // 构建返回日志信息
        Map<String, Object> webLog = new HashMap<>();
        webLog.put("type", "Return Result");
        webLog.put("class", joinPoint.getSignature().getDeclaringTypeName());
        webLog.put("function", joinPoint.getSignature().getName());
        webLog.put("result", result);

        // 使用JSONUtil格式化输出
        LOGGER.info(JSONUtil.toJsonPrettyStr(JSONUtil.parse(webLog)));
    }

    @After("webLog()")
    public void doAfter() {
        // 计算并记录执行时间
        long executionTime = System.currentTimeMillis() - startTimeThreadLocal.get();

        // 构建结束日志信息
        Map<String, Object> webLog = new HashMap<>();
        webLog.put("type", "Request End");
        webLog.put("executionTime", executionTime + " ms");

        // 使用JSONUtil格式化输出
        LOGGER.info(JSONUtil.toJsonPrettyStr(JSONUtil.parse(webLog)));

        // 清理ThreadLocal
        startTimeThreadLocal.remove();
    }
}
