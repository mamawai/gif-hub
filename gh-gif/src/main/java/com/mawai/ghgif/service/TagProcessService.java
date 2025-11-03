package com.mawai.ghgif.service;

import com.mawai.ghgif.constant.TagGifSortType;
import com.mawai.ghgif.vo.GifTagVO;
import com.mawai.ghgif.vo.GifVO;

import java.util.List;

public interface TagProcessService {

    /**
     * 前缀查找标签
     *
     * @param content 查询内容
     * @param pageNum 查询页码
     * @param pageSize 查询每页数量
     * @return 查询结果
     */
    List<String> prefixSearch(String content, int pageNum, int pageSize);

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

    /**
     * 根据 GIF ID 查询标签列表
     *
     * @param gifId GIF ID
     * @return 标签列表（包含ID和名称）
     */
    List<GifTagVO> getTagsByGifId(Long gifId);

    /**
     * 根据标签 ID 查询 GIF 列表（TIME 排序，游标分页）
     *
     * @param tagId 标签 ID
     * @param pageSize 每页数量
     * @param lastId 游标分页最后一条记录 ID（首次查询不传）
     * @param lastValue 游标分页最后一条记录排序值（首次查询不传）
     * @return GIF 列表
     */
    List<GifVO> getGifsByTagId(Long tagId, int pageSize, Long lastId, String lastValue);

}
