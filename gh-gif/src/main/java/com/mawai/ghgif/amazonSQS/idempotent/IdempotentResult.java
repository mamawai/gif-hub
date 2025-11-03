package com.mawai.ghgif.amazonSQS.idempotent;

import lombok.Getter;

/**
 * 幂等性处理结果
 * 
 * @author mawai
 * @since 2025-10-22
 */
@Getter
public class IdempotentResult {

    /**
     * 处理状态
     */
    private final Status status;

    /**
     * 重试次数（仅在失败或异常时有值）
     */
    private final long retryTimes;

    /**
     * 异常信息（仅在异常时有值）
     */
    private final Exception exception;

    private IdempotentResult(Status status, long retryTimes, Exception exception) {
        this.status = status;
        this.retryTimes = retryTimes;
        this.exception = exception;
    }

    /**
     * 消息已处理成功（幂等性检查通过，跳过处理）
     */
    public static IdempotentResult alreadyProcessed() {
        return new IdempotentResult(Status.ALREADY_PROCESSED, 0, null);
    }

    /**
     * 消息正在处理中（获取锁失败）
     */
    public static IdempotentResult processing() {
        return new IdempotentResult(Status.PROCESSING, 0, null);
    }

    /**
     * 处理成功
     */
    public static IdempotentResult success() {
        return new IdempotentResult(Status.SUCCESS, 0, null);
    }

    /**
     * 处理失败（业务逻辑返回 false）
     */
    public static IdempotentResult failed(long retryTimes) {
        return new IdempotentResult(Status.FAILED, retryTimes, null);
    }

    /**
     * 处理异常
     */
    public static IdempotentResult exception(long retryTimes, Exception exception) {
        return new IdempotentResult(Status.EXCEPTION, retryTimes, exception);
    }

    /**
     * 是否需要重新抛出异常（用于事务回滚）
     */
    public boolean shouldThrowException() {
        return status == Status.EXCEPTION;
    }

    /**
     * 处理状态枚举
     */
    public enum Status {
        /**
         * 消息已处理成功（幂等性检查）
         */
        ALREADY_PROCESSED,

        /**
         * 消息正在处理中（获取锁失败）
         */
        PROCESSING,

        /**
         * 处理成功
         */
        SUCCESS,

        /**
         * 处理失败（业务逻辑返回 false）
         */
        FAILED,

        /**
         * 处理异常
         */
        EXCEPTION
    }
}

