package com.mawai.ghgif.controller;

import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghgif.dto.GifDTO;
import com.mawai.ghgif.dto.LikeRequestDTO;
import com.mawai.ghgif.service.GifProcessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/gif")
@Tag(name = "GIF管理", description = "GIF上传和管理相关接口")
public class GifController {

    @Autowired
    private GifProcessService gifProcessService;

    /**
     * 上传gif
     * @param file gif文件
     * @param userId 用户ID
     * @param title 标题（可选）
     * @param description 描述（可选）
     * @return 上传结果
     */
    @Operation(summary = "cloudflare上传gif", description = "cloudflare上传gif", operationId = "r2upload")
    @PostMapping(value = "/r2upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<String> r2upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("userId") Long userId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "description", required = false) String description) {
        try {
            String url = gifProcessService.r2uploadGif(file, userId, title, description);
            return ApiResponse.success(url);
        } catch (Exception e) {
            return ApiResponse.error(500, "上传失败：" + e.getMessage());
        }
    }

    /**
     * 批量上传gif
     * @param files gif文件列表
     * @param userId 用户ID
     * @param titles 标题列表
     * @param descriptions 描述列表
     * @return 上传结果
     */
    @Operation(summary = "批量上传gif", description = "批量上传gif", operationId = "batchUpload")
    @PostMapping(value = "/batchUpload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> batchUpload(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("userId") Long userId,
            @RequestParam(value = "titles", required = false) List<String> titles,
            @RequestParam(value = "descriptions", required = false) List<String> descriptions) {
        try {
            List<String> urls = gifProcessService.batchUploadGif(files, userId, titles, descriptions);
            return ApiResponse.success(urls);
        } catch (Exception e) {
            return ApiResponse.error(500, "批量上传失败：" + e.getMessage());
        }
    }

    /**
     * 更新GIF下载次数
     * @param fileName 文件名
     * @return 更新结果
     */
    @Operation(summary = "更新GIF下载次数", description = "更新GIF下载次数")
    @GetMapping("/record-download")
    public ApiResponse<Boolean> recordDownload(
            @RequestParam("fileName") String fileName) {
        try {
            boolean result = gifProcessService.updateDownloadCount(fileName);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "更新下载次数失败: " + e.getMessage());
        }
    }

    /**
     * 更新点赞次数
     * @param likeRequestDTO 点赞请求
     * @return 更新结果
     */
    @Operation(summary = "更新点赞次数", description = "更新点赞次数")
    @PostMapping("/likeOrDislike")
    public ApiResponse<Boolean> likeOrDislike(@RequestBody LikeRequestDTO likeRequestDTO) {
        try {
            boolean result = gifProcessService.updateLikeCount(
                    likeRequestDTO.getFileId(),
                    likeRequestDTO.getUserId(),
                    likeRequestDTO.getIsLike()
            );
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "更新点赞次数失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户是否喜欢此gif
     */
    @Operation(summary = "获取用户是否喜欢此gif", description = "获取用户是否喜欢此gif")
    @GetMapping("/isLikeThis")
    public ApiResponse<Boolean> isLikeThis(
            @RequestParam("fileId") String fileId,
            @RequestParam("userId") Long userId) {
        try {
            boolean result = gifProcessService.isLikeThis(fileId, userId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "获取用户是否喜欢此gif失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户喜欢列表
     * @param userId 用户ID
     * @return 用户喜欢列表
     */
    @Operation(summary = "获取用户喜欢列表", description = "获取用户喜欢列表")
    @GetMapping("/user-like")
    public ApiResponse<List<GifDTO>> userLike(@RequestParam("userId") Long userId) {
        try {
            List<GifDTO> gifList = gifProcessService.getUserLikeList(userId);
            return ApiResponse.success(gifList);
        } catch (Exception e) {
            return ApiResponse.error(500, "获取用户喜欢列表失败: " + e.getMessage());
        }
    }

    /**
     * 删除(自己上传的)gif
     * @param fileId 文件名
     * @return 删除结果
     */
    @Operation(summary = "删除(自己上传的)gif", description = "删除(自己上传的)gif")
    @DeleteMapping("/{fileId}")
    public ApiResponse<Boolean> delete(@PathVariable String fileId) {
        try {
            boolean result = gifProcessService.deleteGif(fileId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "删除失败：" + e.getMessage());
        }
    }

    /**
     * 分页获取gif列表
     * @param page 页码
     * @param pageSize 每页数量
     * @return gif列表
     */
    @Operation(summary = "分页获取gif列表", description = "分页获取gif列表")
    @GetMapping("/list")
    public ApiResponse<List<GifDTO>> list(
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", required = false, defaultValue = "10") Integer pageSize) {
        try {
            List<GifDTO> gifDTOList = gifProcessService.listGifs(page, pageSize);
            return ApiResponse.success(gifDTOList, "success:" + gifProcessService.getTotalGifCount()); // total 拼在message里面
        } catch (Exception e) {
            return ApiResponse.error(500, "获取列表失败：" + e.getMessage());
        }
    }
    
    /**
     * 分页获取用户上传的gif列表
     * @param userId 用户ID
     * @param page 页码
     * @param pageSize 每页数量
     * @return gif列表
     */
    @Operation(summary = "分页获取用户上传的gif列表", description = "分页获取用户上传的gif列表")
    @GetMapping("/user/{userId}")
    public ApiResponse<List<GifDTO>> listByUser(@PathVariable Long userId,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "pageSize", required = false, defaultValue = "10") Integer pageSize) {
        try {
            Pair<List<GifDTO>, Long> pair = gifProcessService.listGifsByUser(userId, page, pageSize);
            return ApiResponse.success(pair.getLeft(), "success:" + pair.getRight()); // total 拼在message里面
        } catch (Exception e) {
            return ApiResponse.error(500, "获取列表失败：" + e.getMessage());
        }
    }
}
