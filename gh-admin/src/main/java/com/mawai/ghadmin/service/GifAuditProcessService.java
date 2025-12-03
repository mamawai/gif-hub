package com.mawai.ghadmin.service;

import com.mawai.ghadmin.dto.GifAuditDTO;

import java.util.List;

/**
 * GIF审核服务接口
 */
public interface GifAuditProcessService {
    
    /**
     * 获取待审核的GIF列表
     * @param page 页码
     * @param pageSize 每页大小
     * @return 待审核GIF列表
     */
    List<GifAuditDTO> getPendingAuditList(Integer page, Integer pageSize);
    
    /**
     * 审核GIF
     * @param id 审核ID
     * @param status 审核状态：1通过, 0下架
     * @return 是否成功
     */
    boolean auditGif(Long id, Integer status);

    /** 
     * 批量删除下架的GIF以及审核记录
     * @return 是否成功
     */
    boolean deleteBatch();
} 