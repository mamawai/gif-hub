package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.model.GifTag;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;


import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * GIF标签关联表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Mapper
public interface GifTagMapper extends BaseMapper<GifTag> {

    /**
     * 标签AND查询GIF ID列表（按时间排序）- 游标分页
     *
     * @param tagIds 标签ID列表
     * @param tagCount 标签数量
     * @param count 数量
     * @param lastId 最后一条记录的ID
     * @param lastCreatedAt 最后一条记录的创建时间
     * @return GIF ID列表
     */
    List<Long> selectGifIdsByTagsOrderByTime(@Param("tagIds") List<Long> tagIds,
                                                    @Param("tagCount") int tagCount,
                                                    @Param("count") int count,
                                                    @Param("lastId") Long lastId,
                                                    @Param("lastCreatedAt") String lastCreatedAt);

    /**
     * 标签AND查询GIF ID列表（按热度排序）
     *
     * @param tagIds 标签ID列表
     * @param tagCount 标签数量
     * @param offset 偏移量
     * @param count 数量
     * @return GIF ID列表
     */
    List<Long> selectGifIdsByTagsOrderByHot(@Param("tagIds") List<Long> tagIds,
                                                   @Param("tagCount") int tagCount,
                                                   @Param("offset") int offset,
                                                   @Param("count") int count);

    /**
     * 标签AND查询GIF ID列表（随机）- 游标分页
     *
     * @param tagIds 标签ID列表
     * @param tagCount 标签数量
     * @param count 数量
     * @param lastId 最后一条记录的ID
     * @param lastRandomNumber 最后一条记录的随机数
     * @return GIF ID列表
     */
    List<Long> selectGifIdsByTagsOrderByRandom(@Param("tagIds") List<Long> tagIds,
                                                      @Param("tagCount") int tagCount,
                                                      @Param("count") int count,
                                                      @Param("lastId") Long lastId,
                                                      @Param("lastRandomNumber") String lastRandomNumber);
}
