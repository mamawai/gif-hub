package com.mawai.ghweixin.controller;

import cn.dev33.satoken.exception.NotLoginException;
import com.mawai.ghcommon.annotation.RateLimiter;
import com.mawai.ghcommon.constant.RateLimiterType;
import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghweixin.dto.*;
import com.mawai.ghweixin.dto.TurnstileResponse;
import com.mawai.ghweixin.service.EmailAuthService;
import com.mawai.ghweixin.service.ProxyCheckService;
import com.mawai.ghweixin.service.TurnstileService;
import com.mawai.ghweixin.utils.IpUtil;
import com.mawai.ghweixin.utils.TurnstileErrorMapper;
import com.mawai.ghweixin.vo.LoginResultVO;
import com.mawai.ghweixin.vo.UserInfoVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 邮箱认证控制器
 */
@Slf4j
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Tag(name = "邮箱认证接口", description = "邮箱注册登录相关接口")
public class EmailAuthController {

    private final EmailAuthService emailAuthService;
    private final ProxyCheckService proxyCheckService;
    private final TurnstileService turnstileService;

    /**
     * 验证 Turnstile token
     */
    private void validateTurnstile(String token, String clientIp, String email) {
        TurnstileResponse response = turnstileService.validateToken(token, clientIp);
        if (!response.isSuccess()) {
            String errorMsg = TurnstileErrorMapper.getErrorMessage(response.getErrorCodes());
            log.warn("Turnstile验证失败: email={}, ip={}, errors={}, message={}",
                    email, clientIp, response.getErrorCodes(), errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }

    /**
     * 发送验证码
     *
     * @param email 邮箱地址
     * @return 发送结果
     */
    @Operation(summary = "发送验证码", description = "向指定邮箱发送验证码")
    @PostMapping("/code")
    public ApiResponse<Boolean> sendVerificationCode(@RequestParam String email) {
        try {
            boolean result = emailAuthService.sendVerificationCode(email);
            if (result) {
                return ApiResponse.success(true);
            } else {
                return ApiResponse.error(500, "发送验证码失败");
            }
        } catch (Exception e) {
            String errorMessage = e.getMessage();
            return ApiResponse.error(500, errorMessage);
        }
    }

    /**
     * 小程序用户注册（绑定邮箱）
     *
     * @param registerDTO 注册信息
     * @param request HTTP 请求
     * @return 注册结果
     */
    @Operation(summary = "小程序用户注册", description = "小程序用户绑定邮箱（需要已登录微信）")
    @PostMapping("/register")
    public ApiResponse<Boolean> register(@RequestBody EmailRegisterDTO registerDTO, HttpServletRequest request) {
        try {
            // 获取客户端真实 IP（仅接受 Cloudflare 请求）
            String clientIp = IpUtil.getClientIp(request);
            log.info("小程序用户注册请求: email={}, ip={}, fingerprint={}",
                    registerDTO.getEmail(), clientIp, registerDTO.getFingerprint());

            // 小程序注册（需要已登录微信）
            emailAuthService.register(
                    registerDTO.getEmail(),
                    registerDTO.getPassword(),
                    registerDTO.getVerificationCode(),
                    registerDTO.getNickname(),
                    clientIp,
                    registerDTO.getFingerprint()
            );
            return ApiResponse.success(true);
        } catch (RuntimeException e) {
            log.error("小程序用户注册失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "用户注册失败: " + e.getMessage());
        }
    }

    /**
     * Web端用户注册（独立注册）
     *
     * @param registerDTO 注册信息
     * @param request HTTP 请求
     * @return 注册结果（包含token）
     */
    @Operation(summary = "Web端用户注册", description = "Web端独立注册新用户（不需要微信登录）")
    @PostMapping("/web/register")
    public ApiResponse<LoginResultVO> webRegister(@RequestBody EmailRegisterDTO registerDTO, HttpServletRequest request) {
        try {
            // 获取客户端真实 IP（仅接受 Cloudflare 请求）
            String clientIp = IpUtil.getClientIp(request);
            log.info("Web端用户注册请求: email={}, ip={}, fingerprint={}",
                    registerDTO.getEmail(), clientIp, registerDTO.getFingerprint());

            // Web端独立注册（不需要微信登录）
            LoginResultVO result = emailAuthService.webRegister(
                    registerDTO.getEmail(),
                    registerDTO.getPassword(),
                    registerDTO.getVerificationCode(),
                    registerDTO.getNickname(),
                    clientIp,
                    registerDTO.getFingerprint()
            );
            return ApiResponse.success(result);
        } catch (RuntimeException e) {
            log.error("Web端用户注册失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "用户注册失败: " + e.getMessage());
        }
    }

    /**
     * Web端发送验证码
     *
     * @param email 邮箱地址
     * @param turnstileToken Turnstile验证token
     * @param request HTTP请求
     * @return 发送结果
     */
    @Operation(summary = "Web端发送验证码", description = "Web端向已注册邮箱发送验证码")
    @PostMapping("/web/code")
    public ApiResponse<Boolean> sendWebVerificationCode(
            @RequestParam String email,
            @RequestParam String turnstileToken,
            HttpServletRequest request) {
        try {
            validateTurnstile(turnstileToken, IpUtil.getClientIp(request), email);
            boolean result = emailAuthService.sendWebVerificationCode(email);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Web端发送验证码失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * Web端登录（支持密码和验证码两种方式）
     *
     * @param loginDTO 登录信息
     * @return 登录结果（包含token）
     */
    @Operation(summary = "Web端登录", description = "Web端通过邮箱+密码或邮箱+验证码登录")
    @PostMapping("/web/login")
    public ApiResponse<LoginResultVO> webLogin(@RequestBody EmailLoginDTO loginDTO) {
        try {
            LoginResultVO result;
            if (loginDTO.getLoginType() == 1) {
                // 密码登录
                result = emailAuthService.webLogin(loginDTO.getEmail(), loginDTO.getPassword(), null);
            } else if (loginDTO.getLoginType() == 2) {
                // 验证码登录
                result = emailAuthService.webLogin(loginDTO.getEmail(), null, loginDTO.getVerificationCode());
            } else {
                return ApiResponse.error(400, "不支持的登录类型");
            }
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("Web端登录失败: {}", e.getMessage(), e);
            if (e.getMessage().equals("请先注册邮箱")) {
                // 1001 前端跳转到注册
                return ApiResponse.error(1001, e.getMessage(), new LoginResultVO( null));
            }
            return ApiResponse.error(500, "登录失败: " + e.getMessage());
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
        } catch (NotLoginException e) {
            // 让NotLoginException传播到GlobalExceptionHandler
            throw e;
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
    public ApiResponse<UserInfoVO> checkAndGet() {
        try {
            UserInfoVO result = emailAuthService.checkAndGet();
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

    /**
     * 注销账号
     *
     * @param deleteAccountDTO 注销请求
     * @return 注销结果
     */
    @Operation(summary = "注销账号", description = "永久删除账号及所有相关数据，邮箱24小时内无法重新注册")
    @PostMapping("/delete")
    public ApiResponse<Boolean> deleteAccount(@RequestBody DeleteAccountDTO deleteAccountDTO) {
        try {
            boolean result = emailAuthService.deleteAccount(deleteAccountDTO.getPassword());
            if (result) {
                return ApiResponse.success(true);
            } else {
                return ApiResponse.error(500, "注销账号失败");
            }
        } catch (Exception e) {
            log.error("注销账号失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "注销账号失败: " + e.getMessage());
        }
    }

    /**
     * 发送重置密码验证码
     *
     * @param email 邮箱地址
     * @param turnstileToken Turnstile验证token
     * @param request HTTP请求
     * @return 发送结果
     */
    @Operation(summary = "发送重置密码验证码", description = "向已注册邮箱发送重置密码验证码")
    @PostMapping("/reset-password/code")
    public ApiResponse<Boolean> sendResetPasswordCode(
            @RequestParam String email,
            @RequestParam String turnstileToken,
            HttpServletRequest request) {
        try {
            validateTurnstile(turnstileToken, IpUtil.getClientIp(request), email);
            boolean result = emailAuthService.sendResetPasswordCode(email);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("发送重置密码验证码失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 重置密码
     *
     * @param resetPasswordDTO 重置密码请求
     * @return 重置结果
     */
    @Operation(summary = "重置密码", description = "通过邮箱验证码重置密码")
    @PostMapping("/reset-password")
    public ApiResponse<Boolean> resetPassword(@RequestBody ResetPasswordDTO resetPasswordDTO) {
        try {
            boolean result = emailAuthService.resetPassword(
                    resetPasswordDTO.getEmail(),
                    resetPasswordDTO.getVerificationCode(),
                    resetPasswordDTO.getNewPassword()
            );
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("重置密码失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * IP 预检测
     * <p>
     * 用户访问网站时自动调用，返回 IP 的检测结果（detections）
     *
     * @param request HTTP 请求
     * @return 检测结果中的 detections 字段
     */
    @Operation(summary = "IP 预检测", description = "检测用户 IP 的风险性（代理/VPN/Tor等）")
    @RateLimiter(
        type = RateLimiterType.IP_PRE_CHECK,
        permitsPerSecond = 10.0 / 60,
        bucketCapacity = 10,
        message = "IP检测请求过于频繁，请稍后再试",
        global = true
    )
    @PostMapping("/ipPreCheck")
    public ApiResponse<ProxyCheckResponse.Detections> ipPreCheck(HttpServletRequest request) {
        try {
            // 获取客户端真实 IP
            String clientIp = IpUtil.getClientIp(request);
            log.info("IP 预检测请求: ip={}", clientIp);

            // 本地 IP 跳过检测
            if ("127.0.0.1".equals(clientIp)) {
                log.debug("本地 IP，跳过检测");
                ProxyCheckResponse.Detections detections = new ProxyCheckResponse.Detections();
                return ApiResponse.success(detections);
            }

            // 调用 ProxyCheck 检测
            ProxyCheckResponse response = proxyCheckService.checkIp(clientIp);

            // 检查响应状态
            if (response.isSuccess()) {
                ProxyCheckResponse.IpDetails details = response.getFirstIpDetails();
                if (details != null && details.getDetections() != null) {
                    return ApiResponse.success(details.getDetections());
                } else {
                    log.warn("IP 检测返回空数据: ip={}", clientIp);
                    return ApiResponse.error(500, "检测返回数据异常");
                }
            } else if (response.isDenied()) {
                log.error("API 配额用尽: ip={}, message={}", clientIp, response.getMessage());
                return ApiResponse.error(500, "检测服务暂时不可用");
            } else if (response.isError()) {
                log.error("IP 检测失败: ip={}, message={}", clientIp, response.getMessage());
                return ApiResponse.error(500, "IP 检测失败");
            } else {
                return ApiResponse.error(500, "未知错误");
            }
        } catch (Exception e) {
            log.error("IP 预检测异常: {}", e.getMessage(), e);
            return ApiResponse.error(500, "IP 检测异常: " + e.getMessage());
        }
    }
}