package com.mawai.ghweixin.service;

import com.mawai.ghweixin.vo.LoginResultVO;

/**
 * 微信认证服务接口
 */
public interface WeChatAuthService {
    
    /**
     * 微信登录
     *
     * @param code 微信授权code
     * @return 登录结果，包含token和用户信息
     */
    LoginResultVO loginWithWeChat(String code);
}
