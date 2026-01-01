package com.mawai.ghweixin.service.impl;

import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghweixin.config.ProxyCheckConfig;
import com.mawai.ghweixin.dto.ProxyCheckResponse;
import com.mawai.ghweixin.service.ProxyCheckService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ProxyCheck.io IP 代理检测服务实现
 * <p>
 * API 文档: <a href="https://proxycheck.io/api/">https://proxycheck.io/api/</a>
 * <p>
 * 功能特性:
 * - 3个免费API key循环轮换（每次请求都切换）
 * - 6小时缓存
 * - 高风险IP也缓存，下次直接拒绝
 */
@Service
@Slf4j
public class ProxyCheckServiceImpl implements ProxyCheckService {

    private static final String API_URL = "https://proxycheck.io/v3/";
    private static final String REDIS_PREFIX = "proxycheck:";
    private static final int CACHE_HOURS = 6;

    private final RestTemplate restTemplate;
    private final ProxyCheckConfig config;
    private final CacheService cacheService;

    // 当前使用的 API key 索引（每次请求都递增）
    private final AtomicInteger keyIndexCounter = new AtomicInteger(0);

    public ProxyCheckServiceImpl(
            @Qualifier("proxyCheckRestTemplate") RestTemplate restTemplate,
            ProxyCheckConfig config,
            CacheService cacheService) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.cacheService = cacheService;
    }

    @Override
    public ProxyCheckResponse checkIp(String ip) {
        // 检查是否启用
        if (!config.isEnabled()) {
            log.debug("ProxyCheck 检测已禁用，跳过检查");
            return createDisabledResponse();
        }

        // 检查缓存
        String cacheKey = REDIS_PREFIX + ip;
        ProxyCheckResponse cached = cacheService.get(cacheKey);
        if (cached != null) {
            log.debug("从缓存获取 IP 检测结果: {}, status={}", ip, cached.getStatus());
            return cached;
        }

        // 调用 API（循环使用key，失败时尝试下一个）
        ProxyCheckResponse response = callApiWithRetry(ip);

        // 缓存结果（包括高风险IP）
        if (response.isSuccess()) {
            cacheService.set(cacheKey, response, CACHE_HOURS, TimeUnit.HOURS);
            log.debug("缓存 IP 检测结果: {}, status={}", ip, response.getStatus());
        }

        return response;
    }

    /**
     * 调用 API（循环轮换key，失败时重试）
     */
    private ProxyCheckResponse callApiWithRetry(String ip) {
        List<String> apiKeys = config.getApiKeys();
        if (apiKeys == null || apiKeys.isEmpty()) {
            log.error("ProxyCheck API keys 未配置");
            return createErrorResponse("API keys 未配置");
        }

        int attempts = 0;
        int maxAttempts = apiKeys.size();

        while (attempts < maxAttempts) {
            // 循环获取下一个 key（每次调用都递增）
            int keyIndex = keyIndexCounter.getAndIncrement() % apiKeys.size();
            String apiKey = apiKeys.get(keyIndex);

            try {
                ProxyCheckResponse response = callApi(ip, apiKey);

                // 检查响应状态
                if (response.isSuccess()) {
                    // 成功，返回结果
                    return response;
                } else if (response.isDenied()) {
                    // key 次数用尽，尝试下一个 key
                    log.warn("API key[{}] 次数用尽，尝试下一个 key: {}", keyIndex, response.getMessage());
                    attempts++;
                } else if (response.isError()) {
                    // IP 格式错误等，直接返回
                    log.error("IP 格式错误: {}, message={}", ip, response.getMessage());
                    return response;
                } else {
                    // 其他情况，返回结果
                    return response;
                }
            } catch (Exception e) {
                log.error("调用 ProxyCheck API 失败: ip={}, keyIndex={}, error={}", ip, keyIndex, e.getMessage());
                // 尝试下一个 key
                attempts++;
            }
        }

        // 所有 key 都失败
        log.error("所有 API keys 都已用尽或失败: ip={}", ip);
        return createErrorResponse("所有 API keys 次数用尽");
    }

    /**
     * 调用 ProxyCheck API
     */
    private ProxyCheckResponse callApi(String ip, String apiKey) {
        // 构建 URL: https://proxycheck.io/v3/{ip}?key={apiKey}
        String url = API_URL + ip + "?key=" + apiKey;

        log.debug("调用 ProxyCheck API: ip={}", ip);

        // 发起请求
        ProxyCheckResponse response = restTemplate.getForObject(url, ProxyCheckResponse.class);

        // 记录结果
        if (response != null) {
            if (response.isSuccess()) {
                ProxyCheckResponse.IpDetails details = response.getFirstIpDetails();
                if (details != null && details.getDetections() != null) {
                    ProxyCheckResponse.Detections detections = details.getDetections();
                    log.info("IP 检测完成: ip={}, proxy={}, vpn={}, tor={}, compromised={}, hosting={}, risk={}",
                            ip, detections.isProxy(), detections.isVpn(), detections.isTor(),
                            detections.isCompromised(), detections.isHosting(), detections.getRisk());
                }
            } else {
                log.warn("IP 检测返回非成功状态: ip={}, status={}, message={}",
                        ip, response.getStatus(), response.getMessage());
            }
        }

        return response;
    }

    /**
     * 创建禁用状态的响应
     */
    private ProxyCheckResponse createDisabledResponse() {
        ProxyCheckResponse response = new ProxyCheckResponse();
        response.setStatus("ok");
        ProxyCheckResponse.IpDetails details = new ProxyCheckResponse.IpDetails();
        ProxyCheckResponse.Detections detections = new ProxyCheckResponse.Detections();
        details.setDetections(detections);
        response.getIpResults().put("disabled", details);
        return response;
    }

    /**
     * 创建错误响应
     */
    private ProxyCheckResponse createErrorResponse(String message) {
        ProxyCheckResponse response = new ProxyCheckResponse();
        response.setStatus("error");
        response.setMessage(message);
        return response;
    }
}
