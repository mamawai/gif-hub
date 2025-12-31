package com.mawai.ghweixin.service.impl;

import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghweixin.service.RegistrationLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * 注册限制服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RegistrationLimitServiceImpl implements RegistrationLimitService {

    private final CacheService cacheService;

    // Redis 键前缀
    private static final String IP_REGISTER_PREFIX = "IP_REGISTER:";
    private static final String IP_FINGERPRINT_REGISTER_PREFIX = "IP_FINGERPRINT_REGISTER:";

    // IP 限制：1小时内最多3个账号
    private static final int MAX_ACCOUNTS_PER_IP = 3;
    private static final int IP_WINDOW_HOURS = 1;

    // IP+指纹组合限制：24小时内只能1个账号
    private static final int MAX_ACCOUNTS_PER_IP_FINGERPRINT = 1;
    private static final int IP_FINGERPRINT_WINDOW_HOURS = 24;

    @Override
    public boolean isIpAllowedToRegister(String ip) {
        if (!StringUtils.hasText(ip)) {
            log.warn("IP 为空，降级放行");
            return true;
        }

        return checkLimit(IP_REGISTER_PREFIX + ip, MAX_ACCOUNTS_PER_IP, "IP", ip);
    }

    @Override
    public boolean isIpFingerprintAllowedToRegister(String ip, String fingerprint) {
        // 如果指纹为空，降级为只检查 IP
        if (!StringUtils.hasText(fingerprint)) {
            log.warn("指纹为空，降级为只检查 IP: ip={}", ip);
            return isIpAllowedToRegister(ip);
        }

        if (!StringUtils.hasText(ip)) {
            log.warn("IP 为空，降级放行");
            return true;
        }

        String key = IP_FINGERPRINT_REGISTER_PREFIX + ip + ":" + fingerprint;
        return checkLimit(key, MAX_ACCOUNTS_PER_IP_FINGERPRINT, "IP+指纹", ip + ":" + fingerprint);
    }

    @Override
    public void recordIpRegistration(String ip) {
        if (!StringUtils.hasText(ip)) {
            return;
        }

        recordRegistration(IP_REGISTER_PREFIX + ip, IP_WINDOW_HOURS, "IP", ip);
    }

    @Override
    public void recordIpFingerprintRegistration(String ip, String fingerprint) {
        if (!StringUtils.hasText(ip) || !StringUtils.hasText(fingerprint)) {
            return;
        }

        String key = IP_FINGERPRINT_REGISTER_PREFIX + ip + ":" + fingerprint;
        recordRegistration(key, IP_FINGERPRINT_WINDOW_HOURS, "IP+指纹", ip + ":" + fingerprint);
    }

    /**
     * 检查限制（通用方法）
     *
     * @param key Redis 键
     * @param maxCount 最大次数
     * @param type 类型（用于日志）
     * @param identifier 标识符（用于日志）
     * @return true-允许，false-超限
     */
    private boolean checkLimit(String key, int maxCount, String type, String identifier) {
        Integer count = cacheService.get(key);

        if (count != null && count >= maxCount) {
            log.warn("{} 注册次数超限: identifier={}, count={}, limit={}", type, identifier, count, maxCount);
            return false;
        }

        return true;
    }

    /**
     * 记录注册（通用方法）
     *
     * @param key Redis 键
     * @param windowHours 时间窗口（小时）
     * @param type 类型（用于日志）
     * @param identifier 标识符（用于日志）
     */
    private void recordRegistration(String key, int windowHours, String type, String identifier) {
        Long count = cacheService.increment(key);

        // 只在首次设置过期时间，避免每次更新都重置 TTL
        if (count != null && count == 1) {
            cacheService.expire(key, windowHours, TimeUnit.HOURS);
        }

        log.info("记录 {} 注册: identifier={}, count={}", type, identifier, count);
    }
}
