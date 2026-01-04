package com.mawai.ghweixin.service.impl;

import com.mawai.ghweixin.config.TurnstileConfig;
import com.mawai.ghweixin.dto.TurnstileResponse;
import com.mawai.ghweixin.service.TurnstileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Slf4j
@Service
public class TurnstileServiceImpl implements TurnstileService {

    private static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final TurnstileConfig turnstileConfig;
    private final RestTemplate restTemplate;

    public TurnstileServiceImpl(
            TurnstileConfig turnstileConfig,
            @Qualifier("turnstileRestTemplate") RestTemplate restTemplate) {
        this.turnstileConfig = turnstileConfig;
        this.restTemplate = restTemplate;
    }

    @Override
    public TurnstileResponse validateToken(String token, String remoteIp) {
        // 如果未启用，直接返回成功
        if (!turnstileConfig.isEnabled()) {
            log.debug("Turnstile 验证已禁用，跳过验证");
            TurnstileResponse response = new TurnstileResponse();
            response.setSuccess(true);
            return response;
        }

        // 构建请求参数
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("secret", turnstileConfig.getSecretKey());
        params.add("response", token);
        if (remoteIp != null) {
            params.add("remoteip", remoteIp);
        }

        // 构建请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        try {
            TurnstileResponse response = restTemplate.postForObject(
                    SITEVERIFY_URL,
                    request,
                    TurnstileResponse.class
            );

            if (response != null && response.isSuccess()) {
                log.info("Turnstile 验证成功: ip={}", remoteIp);
            } else {
                log.warn("Turnstile 验证失败: ip={}, errors={}",
                        remoteIp, response != null ? response.getErrorCodes() : "null response");
            }

            return response;
        } catch (Exception e) {
            log.error("Turnstile 验证异常: {}", e.getMessage(), e);
            TurnstileResponse errorResponse = new TurnstileResponse();
            errorResponse.setSuccess(false);
            errorResponse.setErrorCodes(List.of("internal-error"));
            return errorResponse;
        }
    }
}
