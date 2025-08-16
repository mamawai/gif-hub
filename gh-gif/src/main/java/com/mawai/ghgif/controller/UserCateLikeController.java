package com.mawai.ghgif.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghgif.service.UserCateLikeService;
import com.mawai.ghmbplus.model.UserCategory;

@RestController
@RequestMapping("/userCategory")
@RequiredArgsConstructor
@Tag(name = "用户喜欢分类", description = "用户喜欢分类相关接口")
public class UserCateLikeController {

    private final UserCateLikeService userCateLikeService;

    /**
     * 用户喜欢分类表新增
     * @param categoryName 分类名称
     * @return 新增结果
     */
    @Operation(summary = "用户喜欢分类表新增", description = "用户喜欢分类表新增")
    @PostMapping("/add")
    public ApiResponse<Boolean> addCategory(@RequestParam("name") String categoryName) {
        try {
            boolean result = userCateLikeService.addCategory(categoryName);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "新增分类失败: " + e.getMessage());
        }
    }

    /**
     * 用户喜欢分类表删除
     * @param categoryId 分类ID
     * @return 删除结果
     */
    @Operation(summary = "用户喜欢分类表删除", description = "用户喜欢分类表删除")
    @DeleteMapping("/{categoryId}")
    public ApiResponse<Boolean> deleteCategory(@PathVariable Long categoryId) {
        try {
            boolean result = userCateLikeService.deleteCategory(categoryId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "删除分类失败: " + e.getMessage());
        }
    } 

    /**
     * 用户喜欢分类表修改
     * @param categoryId 分类ID
     * @param categoryName 分类名称
     * @return 修改结果
     */
    @Operation(summary = "用户喜欢分类表修改", description = "用户喜欢分类表修改")
    @PutMapping("/edit/{categoryId}")
    public ApiResponse<Boolean> updateCategory(@PathVariable Long categoryId, @RequestParam("name") String categoryName) {
        try {
            boolean result = userCateLikeService.updateCategory(categoryId, categoryName);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "修改分类失败: " + e.getMessage());
        }
    }

    /**
     * 用户喜欢分类表查询
     * @return 分类列表
     */
    @Operation(summary = "用户喜欢分类表查询", description = "用户喜欢分类表查询")
    @GetMapping("/list")
    public ApiResponse<List<UserCategory>> getCategoryList() {
        try {
            List<UserCategory> categoryList = userCateLikeService.list();
            return ApiResponse.success(categoryList);
        } catch (Exception e) {
            return ApiResponse.error(500, "查询分类失败: " + e.getMessage());
        }
    }
}
