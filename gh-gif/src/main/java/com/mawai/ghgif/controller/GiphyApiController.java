package com.mawai.ghgif.controller;

import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghgif.gifphy.GiphyApiService;
import com.mawai.ghgif.vo.GiphyGifVO;
import com.mawai.ghgif.vo.GiphyResponseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Giphy API 控制器
 * 
 * @author Mawai
 * @version 1.0
 * @since 2025-11-17
 */
@Tag(name = "Giphy API", description = "Giphy GIF 接口")
@RestController
@RequestMapping("/api/giphy")
@RequiredArgsConstructor
public class GiphyApiController {

    private final GiphyApiService giphyApiService;

    /**
     * 获取热门 GIF
     *
     * @param limit 返回数量，默认25
     * @param offset 偏移量，默认0
     * @return 热门 GIF 列表
     */
    @Operation(summary = "获取热门 GIF", description = "获取当前热门的 GIF 列表")
    @GetMapping("/trending")
    public ApiResponse<GiphyResponseVO> getTrending(
            @Parameter(description = "返回数量", example = "10")
            @RequestParam(defaultValue = "10") int limit,
            @Parameter(description = "偏移量", example = "0")
            @RequestParam(defaultValue = "0") int offset) {
        GiphyResponseVO result = giphyApiService.getTrending(limit, offset);
        return ApiResponse.success(result);
    }

    /**
     * 搜索 GIF
     *
     * @param query 搜索关键词
     * @param limit 返回数量，默认25
     * @param offset 偏移量，默认0
     * @return 搜索结果 GIF 列表
     */
    @Operation(summary = "搜索 GIF", description = "根据关键词搜索 GIF")
    @GetMapping("/search")
    public ApiResponse<GiphyResponseVO> search(
            @Parameter(description = "搜索关键词", required = true, example = "cat")
            @RequestParam String query,
            @Parameter(description = "返回数量", example = "25")
            @RequestParam(defaultValue = "25") int limit,
            @Parameter(description = "偏移量", example = "0")
            @RequestParam(defaultValue = "0") int offset) {
        GiphyResponseVO result = giphyApiService.search(query, limit, offset);
        return ApiResponse.success(result);
    }

    /**
     * 获取随机 GIF
     *
     * @param tag 标签（可选），用于过滤特定主题的 GIF
     * @return 随机 GIF
     */
    @Operation(summary = "获取随机 GIF", description = "获取一个随机 GIF")
    @GetMapping("/random")
    public ApiResponse<GiphyGifVO> getRandom(
            @Parameter(description = "标签（可选）", example = "funny")
            @RequestParam(required = false) String tag) {
        GiphyGifVO result = giphyApiService.getRandom(tag);
        return ApiResponse.success(result);
    }
}
