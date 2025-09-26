package com.mawai.ghgif.service.impl;

import cn.hutool.core.util.StrUtil;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghgif.service.TagProcessService;
import com.mawai.ghgif.util.PinYinUtils;
import com.mawai.ghmbplus.model.Tag;
import com.mawai.ghmbplus.service.TagService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TagProcessServiceImpl implements TagProcessService {

    private final PinYinUtils pinYinUtils;
    private final CacheService cacheService;
    private final TagService tagService;

    private final static String TAG_KEY = "tag:"; // 标签缓存key
    private final static String HOT_TAG_KEY = "hotTag"; // 热门标签缓存key

    @PostConstruct
    public void init() {
        // 获取所有标签添加到缓存按照a-z进行分片
        List<Tag> tagList = tagService.list();
        for (Tag tag : tagList) {
            String tagName = tag.getName();
            char firstLetter = pinYinUtils.getPinyinEngine().getFirstLetter(tagName.charAt(0));
            cacheService.zAdd(TAG_KEY + firstLetter, tagName, 0);
        }

        // hotTag存入缓存
        tagList.sort((o1, o2) -> o2.getUseCount() - o1.getUseCount());
        for (int i = 0; i < Math.min(tagList.size(), 20); i++) {
            Tag tag = tagList.get(i);
            cacheService.zAdd(HOT_TAG_KEY, tag.getName(), tag.getUseCount());
        }
    }

    /**
     * 前缀查找标签
     *
     * @param content 查询内容
     * @param offset 查询偏移量
     * @param count 查询数量
     * @return 查询结果
     */
    @Override
    public List<String> prefixSearch(String content, int offset, int count) {
        if (StrUtil.isBlank(content)) return List.of();
        // 修改content的最后一个字符
        char lastChar = content.charAt(content.length() - 1);
        char newLastChar = (char) (lastChar + 1);
        String newContent = content.substring(0, content.length() - 1) + newLastChar;
        // 获取有序集合指定范围的元素
        return new ArrayList<>(cacheService.zRangeByLex(TAG_KEY + pinYinUtils.getPinyinEngine().getFirstLetter(content.charAt(0)),
                content, newContent, offset, count));
    }

    /**
     * 获取热门标签 -- 前20个
     *
     * @return 热门标签
     */
    @Override
    public List<String> hotTags() {
        Set<String> set = cacheService.zRange(HOT_TAG_KEY, 0, 19);
        return new ArrayList<>(set);
    }


}
