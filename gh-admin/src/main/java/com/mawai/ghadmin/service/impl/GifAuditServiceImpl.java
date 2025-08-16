package com.mawai.ghadmin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.ghadmin.dto.GifAuditDTO;
import com.mawai.ghadmin.dtoMapper.GifAuditParamMapper;
import com.mawai.ghadmin.service.GifAuditService;
import com.mawai.ghmbplus.dao.GifAuditMapper;
import com.mawai.ghmbplus.dao.GifMapper;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.GifAudit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


/**
 * GIF审核服务实现类
 */
@Slf4j
@Service
public class GifAuditServiceImpl implements GifAuditService {

    @Autowired
    private GifAuditMapper gifAuditMapper;
    
    @Autowired
    private GifMapper gifMapper;

    @Autowired
    private GifAuditParamMapper gifAuditParamMapper;

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
        
        // 获取GIF记录
        Gif gif = gifMapper.selectById(audit.getGifId());
        if (gif == null) {
            log.error("GIF记录不存在");
            return false;
        }
        
        // 更新GIF状态
        if (status == 1) {
            // 通过审核
            gif.setStatus((byte) 1); // 正常状态
        } else {
            // 拒绝
            gif.setStatus((byte) 0); // 下架状态
        }
        
        int result = gifMapper.updateById(gif);

        // 更新审核时间
        audit.setAuditAt(LocalDateTime.now());
        gifAuditMapper.updateById(audit);
        
        return result > 0;
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