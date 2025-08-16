package com.mawai.ghweixin.controller;

import com.google.protobuf.Api;
import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghweixin.dto.*;
import com.mawai.ghweixin.service.EmailAuthService;
import com.mawai.ghweixin.vo.LoginResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 邮箱认证控制器
 */
@Slf4j
@RestController
@RequestMapping("/user")
@Tag(name = "邮箱认证接口", description = "邮箱注册登录相关接口")
public class EmailAuthController {

    @Autowired
    private EmailAuthService emailAuthService;

    /**
     * 发送验证码
     *
     * @param email 邮箱地址
     * @return 发送结果
     */
    @Operation(summary = "发送验证码", description = "向指定邮箱发送验证码")
    @PostMapping("/code")
    public ApiResponse<Boolean> sendVerificationCode(@RequestParam String email) {
        boolean result = emailAuthService.sendVerificationCode(email);
        if (result) {
            return ApiResponse.success(true);
        } else {
            return ApiResponse.error(500, "发送验证码失败");
        }
    }

    /**
     * 用户注册
     *
     * @param registerDTO 注册信息
     * @return 注册结果
     */
    @Operation(summary = "用户注册", description = "使用邮箱和验证码注册新用户")
    @PostMapping("/register")
    public ApiResponse<Boolean> register(@RequestBody EmailRegisterDTO registerDTO) {
        try {
            emailAuthService.register(
                    registerDTO.getEmail(),
                    registerDTO.getPassword(),
                    registerDTO.getVerificationCode(),
                    registerDTO.getNickname()
            );
            return ApiResponse.success(true);
        } catch (Exception e) {
            log.error("用户注册失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "用户注册失败: " + e.getMessage());
        }
    }

    /**
     * 登录
     *
     * @param loginDTO 登录信息
     * @return 登录结果
     */
    @Operation(summary = "邮箱登录", description = "通过邮箱密码或验证码登录")
    @PostMapping("/login")
    public ApiResponse<Boolean> login(@RequestBody EmailLoginDTO loginDTO) {
        try {
            Boolean result;
            
            // 根据登录类型调用不同的登录方法
            if (loginDTO.getLoginType() == 1) {
                // 密码登录
                result = emailAuthService.loginByPassword(
                        loginDTO.getEmail(), 
                        loginDTO.getPassword()
                );
            } else if (loginDTO.getLoginType() == 2) {
                // 验证码登录/注册
                result = emailAuthService.loginByCode(
                        loginDTO.getEmail(), 
                        loginDTO.getVerificationCode()
                );
            } else {
                return ApiResponse.error(400, "不支持的登录类型");
            }
            
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("登录失败: {}", e.getMessage(), e);
            if (e.getMessage().equals("请先注册邮箱")) {
                // 1001 前端跳转到注册
                return ApiResponse.error(1001, e.getMessage(), false);
            }
            return ApiResponse.error(500, "登录失败: " + e.getMessage(), false);
        }
    }

    /**
     * 获取当前用户信息
     *
     * @return 用户信息
     */
    @Operation(summary = "检查token并获取用户信息", description = "检查token并获取当前登录用户信息")
    @GetMapping("/checkAndGet")
    public ApiResponse<UserInfoDTO> checkAndGet() {
        try {
            UserInfoDTO result = emailAuthService.checkAndGet();
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("获取用户信息失败: {}", e.getMessage(), e);
            if (e.getMessage().equals("未登录")) {
                return ApiResponse.error(401, e.getMessage());
            } else if (e.getMessage().equals("用户不存在")) {
                return ApiResponse.error(404, e.getMessage());
            } else {
                return ApiResponse.error(500, e.getMessage());
            }
        }   
    }

    /**
     * 退出登录
     *
     * @return 退出结果
     */
    @Operation(summary = "退出登录", description = "退出当前登录状态")
    @PostMapping("/logout")
    public ApiResponse<Boolean> logout() {
        boolean result = emailAuthService.logout();
        if (result) {
            return ApiResponse.success(true);
        } else {
            return ApiResponse.error(500, "退出登录失败");
        }
    }

    /**
     * 检查邮箱是否已验证
     *
     * @return 验证结果
     */
    @Operation(summary = "检查邮箱是否已验证", description = "检查邮箱是否已验证")
    @GetMapping("/isEmailVerified")
    public ApiResponse<Boolean> isEmailVerified() {
        return ApiResponse.success(emailAuthService.isEmailVerified());
    }
} 