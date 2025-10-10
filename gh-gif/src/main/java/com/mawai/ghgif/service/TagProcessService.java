package com.mawai.ghgif.service;

import com.mawai.ghgif.constant.TagGifSortType;
import com.mawai.ghgif.vo.GifVO;

import java.util.List;

public interface TagProcessService {

    /**
     * 前缀查找标签
     *
     * @param content 查询内容
     * @param offset 查询偏移量
     * @param count 查询数量
     * @return 查询结果
     */
    List<String> prefixSearch(String content, int offset, int count);

    /**
     * 获取热门标签 -- 前20个
     *
     * @return 热门标签
     */
    List<String> hotTags();

    /**
     * 获取标签对应的GIF列表（支持多标签AND查询）
     *
     * @param tags 标签列表（多个标签取交集）
     * @param page 页码
     * @param pageSize 每页数量
     * @param sortType 排序类型（time/hot）
     * @param lastId 最后一条记录的ID（TIME排序使用）
     * @param lastValue 最后一条记录的排序字段值（TIME排序使用）
     * @return GIF列表
     */
    List<GifVO> getTagGifs(List<String> tags, int page, int pageSize, TagGifSortType sortType, Long lastId, String lastValue);

}
