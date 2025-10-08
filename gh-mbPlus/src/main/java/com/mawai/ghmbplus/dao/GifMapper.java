package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.model.Gif;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * GIF资源表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Mapper
public interface GifMapper extends BaseMapper<Gif> {

    /**
     * 基于随机数列查询随机GIF
     * @param randomValue 随机数值 (0.0 - 1.0)
     * @return 随机GIF记录
     */
    Gif selectRandom(@Param("randomValue") double randomValue);

    /**
     * 查询最小的随机数的那一行
     * @return 最小的随机数的那一行
     */
    Gif selectMinRandom();

    /**
     * 插入一条数据，并设置随机数
     * @param gif GIF记录
     * @return 插入的行数
     */
    int insertOneWithRandomValue(Gif gif);

    /**
     * 按ID列表批量查询GIF
     * @param gifIds GIF ID列表
     * @return GIF列表（按gifIds顺序返回）
     */
    List<Gif> selectGifsByIds(@Param("gifIds") List<Long> gifIds);
}
