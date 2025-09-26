package com.mawai.ghgif.controller;

import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghgif.service.TagProcessService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

}
