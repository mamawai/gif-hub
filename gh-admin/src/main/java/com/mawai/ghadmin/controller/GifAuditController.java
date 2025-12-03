package com.mawai.ghadmin.controller;

import com.mawai.ghadmin.dto.GifAuditDTO;
import com.mawai.ghadmin.service.GifAuditProcessService;
import com.mawai.ghcommon.domain.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
@Tag(name = "GIF审核管理", description = "GIF审核管理相关接口")
@CrossOrigin // 允许跨域
public class GifAuditController {

    private final GifAuditProcessService gifAuditService;
    
    /**
     * 获取待审核GIF列表
     *
     * @param page 页码，默认1
     * @param pageSize 每页数量，默认10
     * @return 待审核GIF列表
     */
    @Operation(summary = "获取待审核GIF列表", description = "分页获取待审核的GIF列表")
    @GetMapping("/pending")
    public ApiResponse<List<GifAuditDTO>> getPendingList(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize) {
        List<GifAuditDTO> list = gifAuditService.getPendingAuditList(page, pageSize);
        return ApiResponse.success(list);
    }
    
    /**
     * 审核GIF
     *
     * @param id GIF审核记录ID
     * @param status 审核状态
     * @return 审核结果
     */
    @Operation(summary = "审核GIF", description = "通过或拒绝GIF")
    @PostMapping("/process/{id}")
    public ApiResponse<Boolean> auditGif(
            @PathVariable Long id,
            @RequestParam Integer status) {
        boolean result = gifAuditService.auditGif(id, status);
        return ApiResponse.success(result);
    }

    /**
     * 批量删除下架的GIF
     *
     * @return 删除结果
     */
    @Operation(summary = "批量删除审核记录以及下架的GIF", description = "批量删除下架的GIF")
    @DeleteMapping("/deleteBatch")
    public ApiResponse<Boolean> deleteBatch() {
        boolean result;
        try {
            result = gifAuditService.deleteBatch();
        } catch (Exception e) {
            log.info("删除审核记录和GIF出错：{}", e.getMessage());
            return ApiResponse.error(500, "删除审核记录和GIF出错");
        }
        return ApiResponse.success(result);
    }
} 