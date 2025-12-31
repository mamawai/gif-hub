package com.mawai.ghweixin.service.impl;

import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghweixin.config.GetIpIntelConfig;
import com.mawai.ghweixin.dto.GetIpIntelResponse;
import com.mawai.ghweixin.service.GetIpIntelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * GetIPIntel IP 欺诈检测服务实现
 * <p>
 * API 文档: <a href="https://getipintel.net/">getipintel.net</a>
 * API 端点: <a href="http://check.getipintel.net/check.php">...</a>
 * <p>
 * 评分规则:
 * - 0.995+: 几乎肯定是代理/VPN，直接拒绝
 * - 0.99-0.995: 极高可能性，拒绝
 * - 0.95-0.99: 高度可疑，建议拒绝
 * - 0.90-0.95: 中等风险
 * - 小于0.90: 低风险，正常放行
 */
@Service
@Slf4j
public class GetIpIntelServiceImpl implements GetIpIntelService {

    private static final String API_URL = "https://check.getipintel.net/check.php";
    private static final String REDIS_PREFIX = "ipintel:";
    private static final int CACHE_HOURS = 6; // 官方建议缓存不超过 6 小时

    private final RestTemplate restTemplate;
    private final GetIpIntelConfig config;
    private final CacheService cacheService;

    public GetIpIntelServiceImpl(
            @Qualifier("getIpIntelRestTemplate") RestTemplate restTemplate,
            GetIpIntelConfig config,
            CacheService cacheService) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.cacheService = cacheService;
    }

    @Value("${getipintel.enabled}")
    private boolean enabled;

    @Value("${getipintel.flags}")
    private String flags;

    @Value("${getipintel.oflags}")
    private String oflags;


    @Override
    public GetIpIntelResponse checkIp(String ip) {
        // 检查是否启用
        if (!enabled) {
            log.warn("GetIPIntel 检测已禁用，跳过检查");
            return createDisabledResponse(ip);
        }

        // 检查缓存
        String cacheKey = REDIS_PREFIX + ip;
        GetIpIntelResponse cached = cacheService.get(cacheKey);
        if (cached != null) {
            log.debug("从缓存获取 IP 检测结果: {}", ip);
            return cached;
        }

        // 调用 API
        try {
            GetIpIntelResponse response = callApi(ip);

            // 缓存成功结果（6 小时）
            if (response.isSuccess()) {
                cacheService.set(cacheKey, response, CACHE_HOURS, TimeUnit.HOURS);
            }

            return response;

        } catch (Exception e) {
            log.error("调用 GetIPIntel API 失败: ip={}, error={}", ip, e.getMessage(), e);

            // 返回降级响应（允许通过，但记录日志）
            GetIpIntelResponse fallback = new GetIpIntelResponse();
            fallback.setStatus("error");
            fallback.setResult("-999"); // 自定义错误码
            fallback.setMessage("API 调用失败: " + e.getMessage());
            fallback.setQueryIP(ip);
            return fallback;
        }
    }

    /**
     * 调用 GetIPIntel API
     *
     * @param ip IP 地址
     * @return API 响应
     */
    private GetIpIntelResponse callApi(String ip) {
        // 构建 URL
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(API_URL)
                .queryParam("ip", ip)
                .queryParam("contact", config.getContactEmail())
                .queryParam("format", "json")
                .queryParam("flags", flags)    // 默认 m: 快速模式
                .queryParam("oflags", oflags); // 默认 bca: 恶意IP+国家+ASN

        String url = builder.toUriString();

        // 发起请求
        GetIpIntelResponse response = restTemplate.getForObject(url, GetIpIntelResponse.class);

        // 记录结果
        if (response != null && response.isSuccess()) {
            log.info("IP 检测完成: ip={}, score={}, country={}, badIP={}",
                    ip, response.getResult(), response.getCountry(), response.isBadIp());
        } else if (response != null) {
            log.warn("IP 检测返回错误: ip={}, error={}, message={}",
                    ip, response.getResult(), response.getMessage());
        }

        return response;
    }

    /**
     * 创建禁用状态的响应
     */
    private GetIpIntelResponse createDisabledResponse(String ip) {
        GetIpIntelResponse response = new GetIpIntelResponse();
        response.setStatus("success");
        response.setResult("0"); // 分数为 0，表示安全
        response.setQueryIP(ip);
        return response;
    }
}
