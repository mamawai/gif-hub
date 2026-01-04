package com.mawai.ghweixin.service;

import com.mawai.ghweixin.dto.TurnstileResponse;

public interface TurnstileService {

    /**
     * 验证 Turnstile token
     *
     * @param token Turnstile token
     * @param remoteIp 客户端 IP
     * @return 验证结果
     */
    TurnstileResponse validateToken(String token, String remoteIp);
}
