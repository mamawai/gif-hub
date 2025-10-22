package com.mawai.ghgif.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghgif.dto.CommentDTO;
import com.mawai.ghgif.service.CommentProcessService;
import com.mawai.ghgif.vo.CommentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论管理
 */
@RestController
@RequestMapping("/comment")
@RequiredArgsConstructor
@Tag(name = "评论管理", description = "GIF评论相关接口")
public class CommentController {

    private final CommentProcessService commentProcessService;

    /**
     * 发表评论
     */
    @Operation(summary = "发表评论", description = "支持根评论和回复")
    @PostMapping("/add")
    public ApiResponse<Boolean> addComment(@RequestBody @Valid CommentDTO commentDTO) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            boolean result = commentProcessService.addComment(commentDTO, userId);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "发表评论失败：" + e.getMessage());
        }
    }

    /**
     * 获取GIF根评论列表
     */
    @Operation(summary = "获取根评论列表", description = "使用createdAt游标+页码获取GIF的根评论（按时间由远到近，包含热评）")
    @GetMapping("/root/{gifId}")
    public ApiResponse<List<CommentVO>> getRootCommentsByCursor(
            @PathVariable String gifId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer limit) {
        try {
            // 获取当前用户
            Long userId = StpUtil.getLoginIdAsLong();
            List<CommentVO> comments = commentProcessService.getRootCommentsByCursor(
                    gifId, cursor, page, limit, userId
            );
            return ApiResponse.success(comments);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "获取评论列表失败：" + e.getMessage());
        }
    }

    /**
     * 获取子评论列表（游标+页码分页，带ZSet缓存）
     */
    @Operation(summary = "获取子评论列表", description = "使用createdAt游标+页码获取子评论（按时间由远到近）")
    @GetMapping("/children/{rootCommentId}")
    public ApiResponse<List<CommentVO>> getChildCommentsByCursor(
            @PathVariable String rootCommentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursor,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer limit) {
        try {
            // 获取当前用户
            Long userId = StpUtil.getLoginIdAsLong();
            List<CommentVO> comments = commentProcessService.getChildCommentsByCursor(
                    rootCommentId, cursor, page, limit, userId
            );
            return ApiResponse.success(comments);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "获取子评论失败：" + e.getMessage());
        }
    }
    
    /**
     * 获取热门根评论（独立接口）
     */
    @Operation(summary = "获取热门根评论", description = "获取点赞>10的热门根评论，最多3条，前端自行去重和排序")
    @GetMapping("/hot/{gifId}")
    public ApiResponse<List<CommentVO>> getHotRootComments(@PathVariable String gifId) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            List<CommentVO> hotComments = commentProcessService.getHotRootComments(gifId, userId);
            return ApiResponse.success(hotComments);
        } catch (Exception e) {
            return ApiResponse.error(500, "获取热门根评论失败：" + e.getMessage());
        }
    }

    /**
     * 点赞/取消点赞评论
     */
    @Operation(summary = "点赞/取消点赞评论", description = "支持点赞和取消点赞，类似GIF点赞")
    @PostMapping("/like")
    public ApiResponse<Boolean> toggleCommentLike(
            @RequestParam String commentId, 
            @RequestParam Boolean isLike) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            boolean result = commentProcessService.toggleCommentLike(commentId, userId, isLike);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "操作失败：" + e.getMessage());
        }
    }

    /**
     * 删除评论
     */
    @Operation(summary = "删除评论", description = "软删除评论（只能删除自己的）")
    @DeleteMapping("/{commentId}")
    public ApiResponse<Boolean> deleteComment(@PathVariable String commentId) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            boolean result = commentProcessService.deleteComment(commentId, userId);
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(403, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "删除评论失败：" + e.getMessage());
        }
    }
    
    /**
     * 获取用户点赞的评论历史
     */
    @Operation(summary = "我的点赞历史", description = "分页获取用户点赞过的评论列表")
    @GetMapping("/likeHistory")
    public ApiResponse<List<CommentVO>> getLikeHistory(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            List<CommentVO> comments = commentProcessService.getUserLikedComments(
                    userId, pageNum, pageSize
            );
            return ApiResponse.success(comments);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "获取点赞历史失败：" + e.getMessage());
        }
    }
}


