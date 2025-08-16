package com.mawai.ghmbplus.service;

import com.mawai.ghmbplus.model.GifDelete;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * GIF删除表 服务类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
public interface GifDeleteService extends IService<GifDelete> {

    /**
     * 获取需要删除的记录
     * 返回需要在R2删除的文件URL列表，由调用方负责实际删除文件
     *
     * @param batchSize 每批处理的记录数
     * @return 需要删除的记录
     */
    List<GifDelete> getDeleteRecords(int batchSize);

    /**
     * 批量删除GifDelete记录
     *
     * @param recordIds 要删除的记录ID列表
     * @return 是否删除成功
     */
    boolean removeDeleteRecords(List<Long> recordIds);
} 