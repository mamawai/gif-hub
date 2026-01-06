package com.mawai.ghweixin.service;

import com.mawai.ghweixin.vo.LoginResultVO;

public interface LinuxDoOAuthService {

    /**
     * 处理 LinuxDo OAuth 回调
     *
     * @param code        授权码
     * @param fingerprint 浏览器指纹
     * @param clientIp    客户端IP
     * @return 登录结果
     */
    LoginResultVO handleCallback(String code, String fingerprint, String clientIp);
}
