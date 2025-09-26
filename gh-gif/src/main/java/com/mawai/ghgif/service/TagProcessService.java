package com.mawai.ghgif.service;

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
}
