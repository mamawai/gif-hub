package com.mawai.ghweixin.controller;

import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghweixin.config.RsaKeyConfig;
import com.mawai.ghweixin.dto.WeChatDTO;
import com.mawai.ghweixin.service.WeChatAuthService;
import com.mawai.ghweixin.vo.LoginResultVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/wechat")
@RequiredArgsConstructor
@Tag(name = "微信认证接口", description = "微信注册登录相关接口")
public class WeChatAuthController {

    private final WeChatAuthService wechatAuthService;

    /**
     * 微信登录
     *
     * @param weChatDTO 微信登录信息，包含微信授权code
     * @return 登录结果，返回token
     */
    @RequestMapping("/login")
    public ApiResponse<LoginResultVO> login(@RequestBody WeChatDTO weChatDTO) {
        return ApiResponse.success(wechatAuthService.loginWithWeChat(weChatDTO.getCode()));
    }

    /**
     * 获取 RSA 公钥
     * 前端调用此接口获取公钥用于加密密码
     */
    @GetMapping("/public_key")
    public ApiResponse<String> getPublicKey() {
        return ApiResponse.success(RsaKeyConfig.getPublicKeyString());
    }
}
