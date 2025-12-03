package com.mawai.ghaws.sqs.batch;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 通用批量处理器
 * 支持批量大小和超时时间双重触发机制
 * 优化：按需启动定时任务，避免无消息时的资源浪费
 *
 * @author mawai
 * @since 2025-11-20
 */
@Slf4j
public class BatchProcessor<T> {

    private final ConcurrentLinkedQueue<BatchMessageWrapper<T>> buffer = new ConcurrentLinkedQueue<>();
    private final int batchSize;
    private final long timeoutMillis;
    private final Consumer<List<BatchMessageWrapper<T>>> batchHandler;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    private volatile long lastAddTime = 0;
    private volatile boolean isProcessing = false;
    private volatile ScheduledFuture<?> timeoutTask = null;

    public BatchProcessor(int batchSize, long timeoutMillis, Consumer<List<BatchMessageWrapper<T>>> batchHandler) {
        this.batchSize = batchSize;
        this.timeoutMillis = timeoutMillis;
        this.batchHandler = batchHandler;
    }

    /**
     * 添加消息到缓冲区
     */
    public void add(BatchMessageWrapper<T> wrapper) {
        buffer.offer(wrapper);
        lastAddTime = System.currentTimeMillis();
        
        // 启动定时任务（如果尚未启动）
        startTimeoutTaskIfNeeded();
        
        // 检查是否达到批量大小
        if (buffer.size() >= batchSize) {
            triggerBatchProcess("batchSize");
        }
    }

    /**
     * 按需启动定时任务
     */
    private synchronized void startTimeoutTaskIfNeeded() {
        if (timeoutTask == null || timeoutTask.isDone()) {
            timeoutTask = scheduler.scheduleAtFixedRate(
                this::checkTimeout, 
                timeoutMillis, 
                timeoutMillis, 
                TimeUnit.MILLISECONDS
            );
            log.info("定时任务已启动");
        }
    }

    /**
     * 停止定时任务
     */
    private synchronized void stopTimeoutTask() {
        if (timeoutTask != null && !timeoutTask.isDone()) {
            timeoutTask.cancel(false);
            timeoutTask = null;
            log.info("定时任务已停止");
        }
    }

    /**
     * 检查超时
     */
    private void checkTimeout() {
        if (buffer.isEmpty()) {
            stopTimeoutTask();
            return;
        }
        
        if (isProcessing) {
            return;
        }
        
        long elapsed = System.currentTimeMillis() - lastAddTime;
        if (elapsed >= timeoutMillis) {
            triggerBatchProcess("timeout");
        }
    }

    /**
     * 触发批量处理
     * @param triggerReason 触发原因
     */
    private void triggerBatchProcess(String triggerReason) {
        synchronized (this) {
            if (isProcessing || buffer.isEmpty()) {
                return;
            }
            isProcessing = true;
        }

        try {
            List<BatchMessageWrapper<T>> batch = new ArrayList<>();
            BatchMessageWrapper<T> wrapper;
            
            // 取出批量大小的消息
            while (batch.size() < batchSize && (wrapper = buffer.poll()) != null) {
                batch.add(wrapper);
            }

            if (!batch.isEmpty()) {
                log.info("批量处理触发: 原因={}, 消息数={}", triggerReason, batch.size());
                batchHandler.accept(batch);
            }
            
            // 如果 buffer 已空，停止定时任务
            if (buffer.isEmpty()) {
                stopTimeoutTask();
            }
        } finally {
            isProcessing = false;
        }
    }

    /**
     * 关闭处理器
     */
    public void shutdown() {
        stopTimeoutTask();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}