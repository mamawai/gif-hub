package com.mawai.ghgif.amazonSQS.idempotent;

import com.mawai.ghcommon.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * SQS 消息幂等性处理器
 *
 * <p>提供统一的幂等性校验和处理逻辑，确保消息不会被重复处理</p>
 *
 * <p><b>核心机制：</b></p>
 * <ul>
 *   <li>分布式锁（30秒）：防止并发重复处理</li>
 *   <li>处理记录（120秒）：记录已成功处理的消息 ID</li>
 *   <li>重试计数（120秒）：记录失败重试次数</li>
 * </ul>
 *
 * <p><b>Redis Key 设计：</b></p>
 * <ul>
 *   <li>锁 Key：idp:lock:{消费者类型}:{messageId}</li>
 *   <li>处理记录 Key：idp:processed:{消费者类型}:{messageId}</li>
 *   <li>重试计数 Key：idp:retry:{消费者类型}:{messageId}</li>
 * </ul>
 *
 * <p><b>幂等性保证：</b></p>
 * <ul>
 *   <li>已处理检查：快速过滤已成功处理的消息</li>
 *   <li>分布式锁：确保同一消息同一时刻只有一个实例在处理</li>
 *   <li>标记在释放锁之前：保证获取锁成功的线程看到的状态是一致的</li>
 * </ul>
 *
 * @author mawai
 * @since 2025-10-22
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentHandler {

    private final CacheService cacheService;

    /**
     * 分布式锁过期时间（秒）
     * 防止死锁，确保即使处理异常也能自动释放锁
     * 设置为30秒，足够覆盖正常的消息处理时间
     */
    private static final long LOCK_EXPIRE_SECONDS = 30;

    /**
     * 已处理消息记录过期时间（秒）
     * 需要覆盖整个重试周期：SQS可见时间(40秒) × 最大重试次数(3) = 120秒
     */
    private static final long PROCESSED_EXPIRE_SECONDS = 120;

    /**
     * 重试计数过期时间（秒）
     * 与处理记录过期时间保持一致，确保状态同步
     */
    private static final long RETRY_EXPIRE_SECONDS = 120;

    /**
     * 最大重试次数
     */
    private static final int MAX_RETRY_TIMES = 3;

    /**
     * 执行幂等性处理
     *
     * @param consumerType 消费者类型（如：gif、comment、commentlikes、userlikes）
     * @param messageId SQS 消息 ID
     * @param businessLogic 业务逻辑（返回 true 表示成功，false 表示失败）
     * @return 处理结果
     */
    public IdempotentResult execute(String consumerType, String messageId, Supplier<Boolean> businessLogic) {
        String lockKey = "idp:lock:" + consumerType + ":" + messageId;
        String processedKey = "idp:process:" + consumerType + ":" + messageId;
        String retryKey = "idp:retry:" + consumerType + ":" + messageId;

        try {
            // 1. 检查是否已处理成功
            if (isProcessed(processedKey)) {
                log.info("[幂等性] 消息已处理，跳过: type={}, msgId={}", consumerType, messageId);
                return IdempotentResult.alreadyProcessed();
            }

            // 2. 尝试获取分布式锁
            if (!acquireLock(lockKey)) {
                log.warn("[幂等性] 消息处理中，跳过: type={}, msgId={}", consumerType, messageId);
                return IdempotentResult.processing();
            }

            try {
                // 3. 执行业务逻辑
                boolean success = businessLogic.get();

                if (success) {
                    // 4. 标记为已处理
                    markAsProcessed(processedKey);
                    log.info("[幂等性] 处理成功: type={}, msgId={}", consumerType, messageId);
                    return IdempotentResult.success();
                } else {
                    // 5. 业务逻辑返回失败
                    long retryTimes = incrementRetryCount(retryKey);
                    log.warn("[幂等性] 处理失败: type={}, msgId={}, retry={}", consumerType, messageId, retryTimes);
                    return IdempotentResult.failed(retryTimes);
                }

            } finally {
                // 6. 释放锁
                releaseLock(lockKey);
            }

        } catch (Exception e) {
            // 7. 异常处理
            long retryTimes = incrementRetryCount(retryKey);
            log.error("[幂等性] 处理异常: type={}, msgId={}, retry={}", consumerType, messageId, retryTimes, e);
            releaseLock(lockKey);
            return IdempotentResult.exception(retryTimes, e);
        }
    }

    /**
     * 检查消息是否已处理
     */
    private boolean isProcessed(String processedKey) {
        return "1".equals(cacheService.get(processedKey));
    }

    /**
     * 获取分布式锁
     */
    private boolean acquireLock(String lockKey) {
        return cacheService.setIfAbsent(lockKey, "1", LOCK_EXPIRE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 释放分布式锁
     */
    private void releaseLock(String lockKey) {
        try {
            cacheService.delete(lockKey);
        } catch (Exception e) {
            log.error("[幂等性] 释放锁失败: key={}", lockKey, e);
        }
    }

    /**
     * 标记消息为已处理
     */
    private void markAsProcessed(String processedKey) {
        cacheService.set(processedKey, "1", PROCESSED_EXPIRE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 增加重试计数
     */
    private long incrementRetryCount(String retryKey) {
        Long count = cacheService.increment(retryKey, 1);
        cacheService.expire(retryKey, RETRY_EXPIRE_SECONDS, TimeUnit.SECONDS);
        return count != null ? count : 0;
    }

    /**
     * 检查是否超过最大重试次数
     */
    public boolean isExceedMaxRetry(long retryTimes) {
        return retryTimes >= MAX_RETRY_TIMES;
    }

}

