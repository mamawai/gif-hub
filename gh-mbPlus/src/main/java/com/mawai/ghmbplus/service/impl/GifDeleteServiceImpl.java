package com.mawai.ghmbplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.ghmbplus.model.GifDelete;
import com.mawai.ghmbplus.dao.GifDeleteMapper;
import com.mawai.ghmbplus.service.GifDeleteService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 * GIF删除表 服务实现类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Service
public class GifDeleteServiceImpl extends ServiceImpl<GifDeleteMapper, GifDelete> implements GifDeleteService {

    /**
     * 获取需要删除的记录
     *
     * <p>此方法从删除记录表中获取待删除的记录</p>
     *
     * @param batchSize 每批处理的记录数
     * @return 需要删除的记录列表
     */
    @Override
    public List<GifDelete> getDeleteRecords(int batchSize) {
        // 查询删除记录，不考虑过期时间
        LambdaQueryWrapper<GifDelete> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.last("LIMIT " + batchSize);

        List<GifDelete> deleteRecords = baseMapper.selectList(queryWrapper);

        if (deleteRecords.isEmpty()) {
            return Collections.emptyList();
        }
        return deleteRecords;
    }

    /**
     * 批量删除GifDelete记录
     *
     * @param recordIds 要删除的记录ID列表
     * @return 是否删除成功
     * @throws RuntimeException 如果删除过程中发生异常，会抛出运行时异常以触发事务回滚
     */
    @Override
    public boolean removeDeleteRecords(List<Long> recordIds) {
        // 从数据库中删除记录
        boolean success = removeByIds(recordIds);
        if (!success) {
            // 删除失败时抛出异常，触发事务回滚
            throw new RuntimeException("从删除记录表移除记录失败");
        }

        return true;
    }
} 