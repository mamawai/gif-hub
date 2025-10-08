package com.mawai.ghgif.controller;

import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghgif.constant.TagGifSortType;
import com.mawai.ghgif.service.TagProcessService;
import com.mawai.ghgif.vo.GifVO;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tag")
@RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name = "标签管理", description = "标签管理接口")
public class TagController {

    private final TagProcessService tagProcessService;

    /**
     * 前缀查找标签
     * 根据指定的前缀内容进行标签搜索，支持分页功能
     *
     * @param content 要搜索的标签前缀内容
     * @param offset 分页偏移量，从第几个结果开始返回
     * @param count 返回结果数量，限制本次查询返回的标签数量
     * @return 包含匹配标签列表的API响应对象
     */
    @Operation(summary = "前缀查找标签", description = "前缀查找标签")
    @GetMapping("/prefix")
    public ApiResponse<List<String>> prefixSearch(@RequestParam("content") String content,
                                                  @RequestParam("offset") int offset,
                                                  @RequestParam("count") int count) {
        return ApiResponse.success(tagProcessService.prefixSearch(content, offset, count));
    }

    /**
     * 返回前20个最热门的标签
     *
     * @return 20个最热门的标签列表
     */
    @Operation(summary = "查询前20个热门标签", description = "查询前20个热门标签")
    @GetMapping("/hot")
    public ApiResponse<List<String>> hotTags() {
        return ApiResponse.success(tagProcessService.hotTags());
    }

    /**
     * 获取标签GIF列表（支持游标分页）
     * @param tags 标签 最多5个
     * @param offset 偏移量（仅HOT排序使用，其他排序传0）
     * @param count 数量
     * @param sortType 排序类型：TIME/HOT/RANDOM
     * @param lastId 最后一条记录的ID（TIME/RANDOM排序使用，首次查询传null或0）
     * @param lastValue 最后一条记录的排序字段值（TIME传created_at，RANDOM传random_number，首次查询传null）
     * @return gif列表
     */
    @Operation(summary = "获取标签GIF列表", description = "获取标签GIF列表，支持TIME/RANDOM游标分页，HOT使用offset分页")
    @GetMapping(value = {"/tagGifs"})
    public ApiResponse<List<GifVO>> tagGifs(@RequestParam("tags") List<String> tags,
                                            @RequestParam(value = "offset", defaultValue = "0") int offset,
                                            @RequestParam(value = "count", defaultValue = "10") int count,
                                            @RequestParam("sortType") String sortType,
                                            @RequestParam(value = "lastId", required = false) Long lastId,
                                            @RequestParam(value = "lastValue", required = false) String lastValue) {
        try {
            if (tags.size() > 5) return ApiResponse.error(500, "最多支持5个标签");
            List<GifVO> gifVOList = tagProcessService.getTagGifs(tags, offset, count, TagGifSortType.fromCode(sortType), lastId, lastValue);
            return ApiResponse.success(gifVOList, "success:");
        } catch (Exception e) {
            return ApiResponse.error(500, "获取标签gif列表失败：" + e.getMessage());
        }
    }
}
