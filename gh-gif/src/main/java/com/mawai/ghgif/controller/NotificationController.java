package com.mawai.ghgif.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghgif.service.NotificationProcessService;
import com.mawai.ghgif.vo.NotificationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知管理
 *
 * @author mawai
 * @since 2025-12-15
 */
@Slf4j
@RestController
@RequestMapping("/notification")
@RequiredArgsConstructor
@Tag(name = "站内通知管理", description = "站内通知相关接口")
public class NotificationController {

    private final NotificationProcessService notificationProcessService;

    /**
     * 获取通知列表
     */
    @Operation(summary = "获取通知列表", description = "分页获取当前用户的通知列表")
    @GetMapping("/list")
    public ApiResponse<List<NotificationVO>> getNotifications(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            
            // 参数校验
            if (page < 1) {
                return ApiResponse.error(400, "页码必须大于0");
            }
            if (size < 1 || size > 100) {
                return ApiResponse.error(400, "每页数量必须在1-100之间");
            }
            
            List<NotificationVO> notifications = notificationProcessService.getNotifications(userId, page, size);
            return ApiResponse.success(notifications);
        } catch (Exception e) {
            log.error("获取通知列表失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取通知列表失败：" + e.getMessage());
        }
    }

    /**
     * 获取未读通知数量
     */
    @Operation(summary = "获取未读通知数量", description = "获取当前用户的未读通知数量")
    @GetMapping("/unread-count")
    public ApiResponse<Long> getUnreadCount() {
        try {
            Long count = notificationProcessService.getUnreadCount(StpUtil.getLoginIdAsLong());
            return ApiResponse.success(count);
        } catch (Exception e) {
            log.error("获取未读通知数量失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取未读通知数量失败：" + e.getMessage());
        }
    }

    /**
     * 清除未读通知
     */
    @Operation(summary = "清除未读通知", description = "将当前用户的所有未读通知标记为已读")
    @PostMapping("/clear-unread")
    public ApiResponse<Boolean> clearUnread() {
        try {
            // 尽早生成时间戳
            boolean result = notificationProcessService.clearUnread(StpUtil.getLoginIdAsLong(), LocalDateTime.now());
            
            if (result) {
                return ApiResponse.success(true, "清除未读通知成功");
            } else {
                return ApiResponse.error(500, "清除未读通知失败");
            }
        } catch (Exception e) {
            log.error("清除未读通知失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "清除未读通知失败：" + e.getMessage());
        }
    }

    /**
     * 清除单个通知（标记为已读）
     */
    @Operation(summary = "清除单个通知", description = "将指定通知标记为已读，用户点击通知时调用")
    @PostMapping("/clear-one/{notificationId}")
    public ApiResponse<Boolean> clearOne(@PathVariable Long notificationId) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            boolean result = notificationProcessService.clearOne(notificationId, userId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("标记通知为已读失败: notificationId={}, error={}", notificationId, e.getMessage(), e);
            return ApiResponse.error(500, "操作失败：" + e.getMessage());
        }
    }
}

