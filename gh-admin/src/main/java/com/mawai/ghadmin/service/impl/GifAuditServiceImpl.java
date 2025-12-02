package com.mawai.ghadmin.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.ghadmin.dto.GifAuditDTO;
import com.mawai.ghadmin.dtoMapper.GifAuditParamMapper;
import com.mawai.ghadmin.service.GifAuditService;
import com.mawai.ghgif.amazonSQS.message.GifMessage;
import com.mawai.ghgif.constant.MessageType;
import com.mawai.ghgif.service.MessageService;
import com.mawai.ghgif.util.R2FileUtils;
import com.mawai.ghmbplus.dao.GifAuditMapper;
import com.mawai.ghmbplus.dao.GifMapper;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.GifAudit;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * GIF审核服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GifAuditServiceImpl implements GifAuditService {
    private final GifAuditMapper gifAuditMapper;
    private final GifMapper gifMapper;
    private final GifAuditParamMapper gifAuditParamMapper;
    private final MessageService messageService;
    private final R2FileUtils r2FileUtils;

    @Value("${aws.sqs.base-queue-url}")
    private String SQS_QUEUE_URL;

    // S3客户端实例
    private S3Client s3Client;

    @PostConstruct
    private void init() {
        this.s3Client = r2FileUtils.getS3Client();
    }

    @Override
    public List<GifAuditDTO> getPendingAuditList(Integer page, Integer pageSize) {
        // 分页查询待审核GIF -- 审核时间为空
        Page<GifAudit> queryPage = new Page<>(page, pageSize);
        LambdaQueryWrapper<GifAudit> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.isNull(GifAudit::getAuditAt);
        Page<GifAudit> resultPage = gifAuditMapper.selectPage(queryPage, queryWrapper);
        
        List<GifAuditDTO> result = new ArrayList<>();
        for (GifAudit audit : resultPage.getRecords()) {
            result.add(gifAuditParamMapper.toGifAuditDTO(audit));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean auditGif(Long id, Integer status) {
        // 获取审核记录
        GifAudit audit = gifAuditMapper.selectById(id);
        if (audit == null) {
            log.error("审核记录不存在");
            return false;
        }
        String fileUrl = audit.getFileUrl();
        String gifFileName = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
        boolean result = false;
        // 更新GIF状态
        if (status == 1) {
            // 通过审核 构建GIF消息
            GifMessage gifMessage = GifMessage.builder()
                    .userId(audit.getUserId())
                    .title(StringUtils.hasText(audit.getTitle()) ? audit.getTitle() : gifFileName)
                    .fileUrl(fileUrl)
                    .description(audit.getDescription())
                    .tags(audit.getTags())
                    .build();

            // 发送GIF消息到SQS
            messageService.send(JSONUtil.toJsonStr(gifMessage), SQS_QUEUE_URL, MessageType.GIF_MESSAGE);
            result = true;
        } else {
            // 拒绝 删除sqs文件
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(R2FileUtils.BUCKET_NAME)
                            .key(gifFileName)
                            .build()
            );
        }

        // 更新审核时间
        audit.setAuditAt(LocalDateTime.now());
        gifAuditMapper.updateById(audit);
        
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteBatch() {
        // 删除审核记录，把auditAt不为空的记录删除
        LambdaQueryWrapper<GifAudit> auditQW = new LambdaQueryWrapper<>();
        auditQW.isNotNull(GifAudit::getAuditAt);
        int delete1 = gifAuditMapper.delete(auditQW);

        // 删除GIF记录，即遍历GIF表把status为0的记录删除
        LambdaQueryWrapper<Gif> gifQW = new LambdaQueryWrapper<>();
        gifQW.eq(Gif::getStatus, 0);
        int delete2 = gifMapper.delete(gifQW);

        log.info("删除了{}条审核记录，{}条GIF记录", delete1, delete2);

        return delete1 > 0 && delete2 > 0;
    }
} 