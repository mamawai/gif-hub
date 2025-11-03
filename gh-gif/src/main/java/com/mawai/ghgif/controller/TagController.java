package com.mawai.ghgif.controller;

import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghgif.constant.TagGifSortType;
import com.mawai.ghgif.service.TagProcessService;
import com.mawai.ghgif.vo.GifTagVO;
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
     *
     * @param content 标签前缀内容
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 匹配的标签列表
     */
    @Operation(summary = "前缀查找标签", description = "前缀查找标签")
    @GetMapping("/prefix")
    public ApiResponse<List<String>> prefixSearch(@RequestParam("content") String content,
                                                  @RequestParam("pageNum") int pageNum,
                                                  @RequestParam("pageSize") int pageSize) {
        return ApiResponse.success(tagProcessService.prefixSearch(content, pageNum, pageSize));
    }

    /**
     * 查询前20个热门标签
     * 搜索联想时补全
     *
     * @return 热门标签列表
     */
    @Operation(summary = "查询前20个热门标签", description = "查询前20个热门标签")
    @GetMapping("/hot")
    public ApiResponse<List<String>> hotTags() {
        return ApiResponse.success(tagProcessService.hotTags());
    }

    /**
     * 获取标签GIF列表
     *
     * @param tagStr 标签，多个用逗号分隔，最多5个
     * @param page 页码，HOT排序时使用
     * @param pageSize 每页数量，默认10
     * @param sortType 排序类型（TIME-时间排序使用游标分页，HOT-热度排序使用普通分页）
     * @param lastId 游标分页最后一条记录ID，TIME排序首次查询不传
     * @param lastValue 游标分页最后一条记录排序值，TIME排序首次查询不传
     * @return GIF列表
     */
    @Operation(summary = "获取标签GIF列表", description = "获取标签GIF列表，TIME使用游标分页，HOT使用page分页")
    @GetMapping(value = {"/tagGifs"})
    public ApiResponse<List<GifVO>> tagGifs(@RequestParam("tagStr") String tagStr,
                                            @RequestParam(value = "page", defaultValue = "0") int page,
                                            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                            @RequestParam("sortType") String sortType,
                                            @RequestParam(value = "lastId", required = false) Long lastId,
                                            @RequestParam(value = "lastValue", required = false) String lastValue) {
        try {
            List<String> tags = List.of(tagStr.split(","));
            if (tags.size() > 5) return ApiResponse.error(500, "最多支持5个标签");
            List<GifVO> gifVOList = tagProcessService.getTagGifs(tags, page, pageSize, TagGifSortType.fromCode(sortType), lastId, lastValue);
            return ApiResponse.success(gifVOList, "success:");
        } catch (Exception e) {
            return ApiResponse.error(500, "获取标签gif列表失败：" + e.getMessage());
        }
    }

    /**
     * 根据 GIF ID 查询标签列表
     *
     * @param gifId GIF ID
     * @return 标签列表（包含ID和名称，用于点击跳转）
     */
    @Operation(summary = "根据GIF ID查询标签", description = "根据GIF ID查询该GIF的所有标签（包含ID和名称）")
    @GetMapping("/gif/{gifId}")
    public ApiResponse<List<GifTagVO>> getTagsByGifId(@PathVariable Long gifId) {
        try {
            List<GifTagVO> tags = tagProcessService.getTagsByGifId(gifId);
            return ApiResponse.success(tags);
        } catch (Exception e) {
            return ApiResponse.error(500, "查询GIF标签失败：" + e.getMessage());
        }
    }

    /**
     * 根据标签 ID 查询 GIF 列表（TIME 排序，游标分页）
     *
     * @param tagId 标签 ID
     * @param pageSize 每页数量，默认 10
     * @param lastId 游标分页最后一条记录 ID（首次查询不传）
     * @param lastValue 游标分页最后一条记录排序值（首次查询不传）
     * @return GIF 列表
     */
    @Operation(summary = "根据标签ID查询GIF列表", description = "根据标签ID查询GIF列表，TIME排序使用游标分页")
    @GetMapping("/getGifBy/{tagId}")
    public ApiResponse<List<GifVO>> getGifsByTagId(@PathVariable Long tagId,
                                                    @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                                    @RequestParam(value = "lastId", required = false) Long lastId,
                                                    @RequestParam(value = "lastValue", required = false) String lastValue) {
        try {
            List<GifVO> gifVOList = tagProcessService.getGifsByTagId(tagId, pageSize, lastId, lastValue);
            return ApiResponse.success(gifVOList);
        } catch (Exception e) {
            return ApiResponse.error(500, "根据标签ID查询GIF列表失败：" + e.getMessage());
        }
    }
}
