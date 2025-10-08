package com.mawai.ghgif.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghgif.constant.TagGifSortType;
import com.mawai.ghgif.modelMapper.GifParamMapper;
import com.mawai.ghgif.service.TagProcessService;
import com.mawai.ghgif.util.PinYinUtils;
import com.mawai.ghgif.vo.GifVO;
import com.mawai.ghmbplus.dao.GifMapper;
import com.mawai.ghmbplus.dao.GifTagMapper;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.model.Tag;
import com.mawai.ghmbplus.service.TagService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TagProcessServiceImpl implements TagProcessService {

    private final PinYinUtils pinYinUtils;
    private final CacheService cacheService;
    private final TagService tagService;
    private final GifTagMapper gifTagMapper;
    private final GifMapper gifMapper;
    private final GifParamMapper gifParamMapper;

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

    /**
     * 获取标签对应的GIF列表（支持多标签AND查询）
     *
     * @param tags 标签列表（多个标签取交集）
     * @param offset 偏移量（仅HOT排序使用）
     * @param count 数量
     * @param sortType 排序类型
     * @param lastId 最后一条记录的ID（TIME/RANDOM排序使用）
     * @param lastValue 最后一条记录的排序字段值（TIME/RANDOM排序使用）
     * @return GIF列表
     */
    @Override
    public List<GifVO> getTagGifs(List<String> tags, int offset, int count, TagGifSortType sortType, Long lastId, String lastValue) {
        if (tags == null || tags.isEmpty() || count <= 0) {
            return Collections.emptyList();
        }

        try {
            // 1. 查询所有标签ID
            List<Tag> tagEntities = tagService.list(
                    new LambdaQueryWrapper<Tag>().in(Tag::getName, tags)
            );

            // 如果有标签不存在，直接返回空（AND逻辑，缺一不可）
            if (tagEntities.size() != tags.size()) {
                log.info("部分标签不存在，查询标签: {}, 找到: {}", tags, tagEntities.size());
                return Collections.emptyList();
            }

            List<Long> tagIds = tagEntities.stream()
                    .map(Tag::getId)
                    .collect(Collectors.toList());

            // 2. 查询GIF ID列表
            List<Long> gifIds = getGifIdsByTags(tagIds, offset, count, sortType, lastId, lastValue);

            if (gifIds.isEmpty()) {
                return Collections.emptyList();
            }

            // 3. 批量查询GIF详情
            List<Gif> gifs = gifMapper.selectGifsByIds(gifIds);

            // 4. 按照原始 gifIds 顺序重新排列
            return sortGifsByIds(gifs, gifIds);
        } catch (Exception e) {
            log.error("查询标签{}的GIF列表失败: {}", tags, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 根据标签ID列表查询GIF ID列表（支持单标签和多标签）
     * @param tagIds 标签id列表
     * @param offset 偏移量（仅HOT排序使用）
     * @param count 数量
     * @param sortType 排序类型
     * @param lastId 最后一条记录的ID（TIME/RANDOM排序使用）
     * @param lastValue 最后一条记录的排序字段值（TIME/RANDOM排序使用）
     * @return GIF ID列表
     */
    private List<Long> getGifIdsByTags(List<Long> tagIds, int offset, int count, TagGifSortType sortType, Long lastId, String lastValue) {
        return switch (sortType) {
            case TIME -> gifTagMapper.selectGifIdsByTagsOrderByTime(
                    tagIds, tagIds.size(), count, lastId, lastValue);
            case HOT -> gifTagMapper.selectGifIdsByTagsOrderByHot(
                    tagIds, tagIds.size(), offset, count);
            case RANDOM -> gifTagMapper.selectGifIdsByTagsOrderByRandom(
                    tagIds, tagIds.size(), count, lastId, lastValue);
        };
    }


    /**
     * 按照指定的ID顺序重新排列GIF列表并转换为VO
     * 
     * @param gifs 查询到的GIF列表（无序）
     * @param gifIds 期望的ID顺序
     * @return 按gifIds顺序排列的GifVO列表
     */
    private List<GifVO> sortGifsByIds(List<Gif> gifs, List<Long> gifIds) {
        // 1. 构建 ID -> Gif 的映射表
        Map<Long, Gif> gifMap = gifs.stream()
                .collect(Collectors.toMap(Gif::getId, gif -> gif));
        
        // 2. 按照原始gifIds顺序提取并转换为VO
        List<GifVO> result = new ArrayList<>(gifIds.size());
        for (Long gifId : gifIds) {
            Gif gif = gifMap.get(gifId);
            if (gif != null) {
                result.add(gifParamMapper.toGifVO(gif));
            }
        }
        
        return result;
    }
}
