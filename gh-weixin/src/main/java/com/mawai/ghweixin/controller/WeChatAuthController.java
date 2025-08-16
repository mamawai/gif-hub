package com.mawai.ghweixin.controller;

import com.mawai.ghcommon.domain.ApiResponse;
import com.mawai.ghweixin.dto.WeChatDTO;
import com.mawai.ghweixin.service.WeChatAuthService;
import com.mawai.ghweixin.vo.LoginResultVO;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @RequestMapping("/login")
    public ApiResponse<LoginResultVO> login(@RequestBody WeChatDTO weChatDTO) {
        return ApiResponse.success(wechatAuthService.loginWithWeChat(weChatDTO.getCode()));
    }
}
