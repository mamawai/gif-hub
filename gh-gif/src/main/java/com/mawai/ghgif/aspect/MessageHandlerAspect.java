package com.mawai.ghgif.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * GIF消息处理执行时间记录切面
 * 专门用于记录GifMessageConsumer.handle方法的执行时间
 */
@Slf4j
@Aspect
@Component
@Order(2) // 设置为比LogAspect更低的优先级
public class MessageHandlerAspect {

    /**
     * 定义切入点：所有MessageConsumer的handle方法
     */
    @Pointcut("execution(* com.mawai.ghgif.amazonSQS.consumer.*.handle(..))")
    public void handlePointcut() {
    }

    /**
     * 环绕通知：记录handle方法的执行时间
     */
    @Around("handlePointcut()")
    public Object recordExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        // 记录开始时间
        long startTime = System.currentTimeMillis();
        
        // 获取消息内容用于日志
        Object[] args = joinPoint.getArgs();
        String messageId = "unknown";
        if (args.length > 0 && args[0] instanceof Message message) {
            messageId = message.messageId();
        }
        
        log.info("=== GIF消息处理开始 === MessageID: {}", messageId);

        boolean hasException = false;
        String exceptionMessage = null;
        
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            hasException = true;
            exceptionMessage = throwable.getMessage();
            log.error("=== GIF消息处理异常 === 异常信息: {}", exceptionMessage);
            throw throwable;
        } finally {
            // 记录结束时间和执行耗时
            long executionTime = System.currentTimeMillis() - startTime;
            
            if (hasException) {
                log.warn("=== GIF消息处理结束（异常） === 总耗时: {} ms, 异常: {}", executionTime, exceptionMessage);
            } else {
                log.info("=== GIF消息处理结束（正常） === 总耗时: {} ms", executionTime);
            }
        }
    }
}
