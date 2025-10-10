package com.mawai.ghmbplus.dao;

import com.mawai.ghmbplus.model.Tag;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 标签表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /**
     * 插入或更新标签，处理并发问题
     * @param tag 标签对象
     * @return 影响行数
     */
    int insertOrUpdateTag(Tag tag);

    /**
     * 根据名称查询标签
     * @param name 标签名称
     * @return 标签对象
     */
    Tag selectByName(@Param("name") String name);

    /**
     * 根据标签ID更新使用次数
     * @param tagIdCountMap 标签ID和使用次数的映射
     */
    void updateUseCountByMap(@Param("tagIdCountMap") Map<Long, Long> tagIdCountMap);

    /**
     * 查询最热门的20个标签
     * @return 最热门的标签列表
     */
    List<Tag> selectHotTags();

    /**
     * 根据标签名称查询标签ID
     * @param tags 标签
     * @return 标签列表
     */
    List<Long> selectIdsByNames(@Param("tags") List<String> tags);
}
