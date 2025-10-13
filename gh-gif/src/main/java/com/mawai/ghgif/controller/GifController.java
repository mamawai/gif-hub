package com.mawai.ghgif.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghgif.dto.BatchGifUploadDTO;
import com.mawai.ghgif.dto.GifDTO;
import com.mawai.ghgif.dto.LikeRequestDTO;
import com.mawai.ghgif.exception.RateLimitException;
import com.mawai.ghgif.service.GifProcessService;
import com.mawai.ghgif.service.ValidationService;
import com.mawai.ghgif.vo.GifVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/gif")
@RequiredArgsConstructor
@Tag(name = "GIF管理", description = "GIF上传和管理相关接口")
public class GifController {

    private final GifProcessService gifProcessService;
    private final ValidationService validationService;

    /**
     * 上传GIF到Cloudflare R2
     *
     * @param gifDTO GIF上传请求，包含文件、标签等信息
     * @return 上传成功后的GIF访问URL
     */
    @Operation(summary = "cloudflare上传gif", description = "cloudflare上传gif", operationId = "r2upload")
    @PostMapping(value = "/r2upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<String> r2upload(@ModelAttribute GifDTO gifDTO) {
        try {
            // 1. 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            gifDTO.setUserId(userId);

            // 2. 验证文件
            String fileValidation = validationService.validateFile(gifDTO.getFile());
            if (fileValidation != null) return ApiResponse.error(400, fileValidation);

            // 3. 执行上传
            String url = gifProcessService.r2uploadGif(gifDTO);
                
            return ApiResponse.success(url);
        } catch (RateLimitException e) {
            // 重新抛出限流异常为了区分异常，让 GlobalExceptionHandler 处理
            throw e;
        } catch (Exception e) {
            return ApiResponse.error(500, "上传失败：" + e.getMessage());
        }
    }

    /**
     * 批量上传GIF到Cloudflare R2
     *
     * @param batchUploadDTO 批量上传请求
     * @return 上传成功的GIF访问URL列表
     */
    @Operation(summary = "cloudflare批量上传gif", description = "cloudflare批量上传gif", operationId = "r2batchUpload")
    @PostMapping(value = "/r2BatchUpload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<String>> batchUpload(@ModelAttribute BatchGifUploadDTO batchUploadDTO) {
        try {
            // 1. 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            batchUploadDTO.setUserId(userId);

            // 2. 基础参数验证
            String validationError = batchUploadDTO.validate();
            if (validationError != null) return ApiResponse.error(400, validationError);

            // 3. 验证所有文件
            String fileValidation = validationService.validateFiles(batchUploadDTO.getFiles());
            if (fileValidation != null) return ApiResponse.error(400, fileValidation);
            
            // 4. 转换为GifDTO列表
            List<GifDTO> gifsDTO = batchUploadDTO.toGifDTOList();
            
            // 5. 执行批量上传
            List<String> urls = gifProcessService.r2batchUploadGif(gifsDTO);

            return ApiResponse.success(urls, "成功上传 " + urls.stream().filter(url -> !url.isBlank()).count() + " 个文件");
        } catch (Exception e) {
            return ApiResponse.error(500, "批量上传失败：" + e.getMessage());
        }
    }

    /**
     * 更新GIF下载次数
     *
     * @param fileId GIF文件ID
     * @return 更新结果
     */
    @Operation(summary = "更新GIF下载次数", description = "更新GIF下载次数")
    @GetMapping("/download")
    public ApiResponse<Boolean> recordDownload(@RequestParam("fileId") String fileId) {
        try {
            boolean result = gifProcessService.updateDownloadCount(fileId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "更新下载次数失败: " + e.getMessage());
        }
    }

    /**
     * 更新GIF查看次数（含限流保护）
     *
     * @param fileId GIF文件ID
     * @return 更新结果
     */
    @Operation(summary = "更新查看次数", description = "更新查看次数")
    @GetMapping("/view/{fileId}")
    public ApiResponse<Boolean> updateViewCount(@PathVariable String fileId) {
        try {
            boolean result = gifProcessService.updateViewCount(fileId);
            return ApiResponse.success(result);
        } catch (RateLimitException e) {
            return ApiResponse.success(false, "更新查看次数频繁");
        } catch (Exception e) {
            return ApiResponse.error(500, "更新查看次数失败: " + e.getMessage());
        }
    }

    /**
     * 点赞或取消点赞GIF
     *
     * @param likeRequestDTO 点赞请求，包含文件ID、分类ID和点赞状态
     * @return 操作结果
     */
    @Operation(summary = "更新点赞次数", description = "更新点赞次数")
    @PostMapping("/likeOrDislike")
    public ApiResponse<Boolean> likeOrDislike(@RequestBody LikeRequestDTO likeRequestDTO) {
        try {
            // 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            
            boolean result = gifProcessService.updateLikeCount(
                    likeRequestDTO.getFileId(),
                    likeRequestDTO.getUserLikeCategoryId(),
                    userId,
                    likeRequestDTO.getIsLike()
            );
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "更新点赞次数失败: " + e.getMessage());
        }
    }

    /**
     * 查询当前用户是否喜欢此GIF
     *
     * @param fileId GIF文件ID
     * @return true-已喜欢，false-未喜欢
     */
    @Operation(summary = "获取用户是否喜欢此gif", description = "获取用户是否喜欢此gif")
    @GetMapping("/isLikeThis")
    public ApiResponse<Boolean> isLikeThis(@RequestParam("fileId") String fileId) {
        try {
            // 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            
            boolean result = gifProcessService.isLikeThis(fileId, userId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "获取用户是否喜欢此gif失败: " + e.getMessage());
        }
    }

    /**
     * 按分类分页查询用户喜欢的GIF列表
     *
     * @param categoryId 分类ID
     * @param pageNum 页码，默认1
     * @param pageSize 每页数量，默认10
     * @return 用户喜欢的GIF列表
     */
    @Operation(summary = "按分类分页获取用户喜欢列表", description = "按分类分页获取用户喜欢列表")
    @GetMapping("/likeByCategory")
    public ApiResponse<List<GifVO>> userLike(
            @RequestParam(value = "categoryId") Long categoryId,
            @RequestParam(value = "pageNum", required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(value = "pageSize", required = false, defaultValue = "10") Integer pageSize) {
        try {
            // 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            
            List<GifVO> gifList = gifProcessService.listUserLikes(userId, categoryId, pageNum, pageSize);
            return ApiResponse.success(gifList);
        } catch (Exception e) {
            return ApiResponse.error(500, "获取用户喜欢列表失败: " + e.getMessage());
        }
    }

    /**
     * 删除自己上传的GIF
     *
     * @param fileId GIF文件ID
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
     * 随机获取GIF列表
     *
     * @param lastId 上次查询最后一个GIF的ID，用于分页加载
     * @return 随机GIF列表
     */
    @Operation(summary = "随机获取gif列表", description = "随机获取gif列表")
    @GetMapping(value = {"/randomGifs", "/randomGifs/{lastId}"})
    public ApiResponse<List<GifVO>> randomList(@PathVariable(required = false) String lastId) {
        try {
            List<GifVO> gifVOList = gifProcessService.getRandomGifs(lastId);
            return ApiResponse.success(gifVOList, "success:" + gifProcessService.getTotalGifCount()); // total 拼在message里面
        } catch (Exception e) {
            return ApiResponse.error(500, "获取随机gif列表失败：" + e.getMessage());
        }
    }

    /**
     * 随机获取一个GIF
     *
     * @return 随机GIF
     */
    @Operation(summary = "获取一个随机gif", description = "获取一个随机gif")
    @GetMapping("/randomGif")
    public ApiResponse<GifVO> getRandomGif() {
        try {
            GifVO gifVO = gifProcessService.getRandomGif();
            return ApiResponse.success(gifVO);
        } catch (Exception e) {
            return ApiResponse.error(500, "获取随机gif失败：" + e.getMessage());
        }
    }
    
    /**
     * 分页查询当前用户上传的GIF列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页数量，默认10
     * @return 当前用户上传的GIF列表
     */
    @Operation(summary = "分页获取当前用户上传的gif列表", description = "分页获取当前用户上传的gif列表")
    @GetMapping("/my")
    public ApiResponse<List<GifVO>> listMyGifs(
            @RequestParam(value = "pageNum", required = false, defaultValue = "1") Integer pageNum ,
            @RequestParam(value = "pageSize", required = false, defaultValue = "10") Integer pageSize) {
        try {
            // 获取当前登录用户ID
            Long userId = StpUtil.getLoginIdAsLong();
            
            Pair<List<GifVO>, Long> pair = gifProcessService.listGifsByUser(userId, pageNum, pageSize);
            return ApiResponse.success(pair.getLeft(), "success:" + pair.getRight()); // total 拼在message里面
        } catch (Exception e) {
            return ApiResponse.error(500, "获取当前用户上传的gif列表失败：" + e.getMessage());
        }
    }
}
