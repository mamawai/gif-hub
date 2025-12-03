package com.mawai.ghgif.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.ghaws.r2.R2FileUtils;
import com.mawai.ghgif.event.GifDeleteEvent;
import com.mawai.ghmbplus.model.GifDelete;
import com.mawai.ghmbplus.model.GifDeleteFailed;
import com.mawai.ghmbplus.model.GifTag;
import com.mawai.ghmbplus.service.GifDeleteFailedService;
import com.mawai.ghmbplus.service.GifDeleteService;
import com.mawai.ghmbplus.service.GifTagService;
import com.mawai.ghmbplus.dao.TagMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GIF删除事件监听器
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class GifDeleteEventListener {

    private final GifDeleteService gifDeleteService;
    private final GifDeleteFailedService gifDeleteFailedService;
    private final TagMapper tagMapper;
    private final GifTagService gifTagService;
    private final R2FileUtils r2FileUtils;

    /**
     * 清理已删除的 GIF 文件（事件监听）
     *
     * <p>
     * 监听 {@link GifDeleteEvent} 事件，批量删除 R2 存储上的垃圾文件，
     * 并清理数据库中的删除记录。
     * </p>
     *
     * <p>
     * <b>处理流程：</b>
     * </p>
     * <ol>
     * <li>从 gif_delete 表获取待删除记录</li>
     * <li>批量删除 R2 存储上的文件（使用 S3 批量删除 API）</li>
     * <li>更新 tag 表的使用次数，删除 gif_tag 关联</li>
     * <li>删除成功的记录从 gif_delete 表移除</li>
     * <li>删除失败的记录保存到 gif_delete_failed 表，等待人工处理</li>
     * </ol>
     *
     * <p>
     * <b>注意：</b>不加 try-catch，让事务回滚机制生效
     * </p>
     *
     * @param event GIF 删除事件，包含删除数量和批次大小
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("taskExecutor")
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void clearDeletedGifs(GifDeleteEvent event) {
        log.info("开始清理删除记录表中的数据和R2垃圾文件...总删除数量为:{}", event.getDelCount());

        List<GifDelete> deleteRecords = gifDeleteService.getDeleteRecords(event.getBatchSize());

        if (deleteRecords.isEmpty()) {
            log.info("没有需要清理的删除记录");
            return;
        }

        log.info("获取到{}条需要从R2删除的文件记录", deleteRecords.size());

        S3Client s3Client = r2FileUtils.getS3Client();

        List<ObjectIdentifier> objectsToDelete = new ArrayList<>();
        List<String> gifTagsToDelete = new ArrayList<>();
        int failUrls = 0;

        for (GifDelete gifDelete : deleteRecords) {
            String fileUrl = gifDelete.getFileUrl();
            String objectKey = extractObjectKeyFromUrl(fileUrl);

            if (objectKey != null) {
                objectsToDelete.add(
                        ObjectIdentifier.builder()
                                .key(objectKey)
                                .build());
            } else {
                failUrls++;
                log.error("无法从URL提取对象键: {}", fileUrl);
            }

            if (gifDelete.getFileId() != null)
                gifTagsToDelete.add(gifDelete.getFileId());
        }

        if (objectsToDelete.isEmpty()) {
            log.info("没有有效的对象需要删除");
            return;
        }

        DeleteObjectsResponse deleteResponse = s3Client.deleteObjects(
                DeleteObjectsRequest.builder()
                        .bucket(R2FileUtils.BUCKET_NAME)
                        .delete(
                                Delete.builder()
                                        .objects(objectsToDelete)
                                        .quiet(true)
                                        .build())
                        .build());

        Set<String> failedKeys = new HashSet<>();

        if (deleteResponse.hasErrors() && !deleteResponse.errors().isEmpty()) {
            deleteResponse.errors().forEach(
                    error -> {
                        failedKeys.add("https://mynnmy.top/" + error.key());
                        log.error("删除对象失败: 键={}, 错误码={}, 消息={}",
                                error.key(), error.code(), error.message());
                    });
        }

        if (gifTagsToDelete.isEmpty()) {
            log.info("没有有效的gifTag需要删除");
        } else {
            List<GifTag> gifTags = gifTagService
                    .list(new LambdaQueryWrapper<GifTag>().in(GifTag::getGifId, gifTagsToDelete));
            if (!gifTags.isEmpty()) {
                Map<Long, Long> tagIdCountMap = gifTags.stream()
                        .collect(Collectors.groupingBy(GifTag::getTagId, Collectors.counting()));
                gifTagService.remove(new LambdaQueryWrapper<GifTag>().in(GifTag::getGifId, gifTagsToDelete));
                tagMapper.updateUseCountByMap(tagIdCountMap);
            }
        }

        log.info("成功从R2批量删除了{}个文件，失败{}个", deleteRecords.size() - failedKeys.size(), failedKeys.size());
        if (failUrls > 0) {
            log.warn("有{}个URL无法解析为对象键", failUrls);
        }

        List<Long> successIds = deleteRecords.stream()
                .filter(record -> !failedKeys.contains(record.getFileUrl()))
                .map(GifDelete::getId).toList();

        if (!successIds.isEmpty()) {
            boolean dbDeleteSuccess = gifDeleteService.removeDeleteRecords(successIds);
            if (dbDeleteSuccess) {
                log.info("成功从数据库中删除了{}条记录", successIds.size());
            } else {
                log.error("从数据库中删除记录失败");
            }
        }

        if (!failedKeys.isEmpty()) {
            gifDeleteFailedService.saveBatch(
                    failedKeys.stream()
                            .map(key -> new GifDeleteFailed()
                                    .setFileUrl("https://mynnmy.top/" + key)
                                    .setCreatedAt(LocalDateTime.now()))
                            .toList());
        }
    }

    /**
     * 从 URL 中提取 S3 对象键
     *
     * @param url 文件 URL
     * @return S3 对象键，如果 URL 为空则返回 null
     */
    private String extractObjectKeyFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        return url.substring(19);
    }
}