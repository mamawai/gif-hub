package com.mawai.ghweixin.utils;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * IP 地址工具类
 * 用于获取通过 Cloudflare 代理的客户端真实 IP
 */
@Slf4j
public class IpUtil {

    private static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final String CF_RAY = "CF-Ray";
    private static final String LOCALHOST_IPV6 = "0:0:0:0:0:0:0:1";
    private static final String LOCALHOST_IPV4 = "127.0.0.1";

    // 是否启用 Cloudflare 验证（默认启用，可通过配置类修改）
    private static boolean cfValidationEnabled = true;

    /**
     * 设置 Cloudflare 验证开关（由配置类调用）
     */
    public static void setCfValidationEnabled(boolean enabled) {
        cfValidationEnabled = enabled;
        log.info("Cloudflare IP 验证已{}", enabled ? "启用" : "禁用");
    }

    /**
     * 获取客户端真实 IP
     *
     * @param request HTTP 请求对象
     * @return 客户端真实 IP
     * @throws InvalidRequestException 非 Cloudflare 请求且验证已启用
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            throw new InvalidRequestException("请求对象为空");
        }

        String cfIp = request.getHeader(CF_CONNECTING_IP);
        String remoteAddr = request.getRemoteAddr();

        // 如果禁用验证，直接返回 RemoteAddr
        if (!cfValidationEnabled) {
            log.debug("Cloudflare 验证已禁用，使用 RemoteAddr: {}", remoteAddr);
            return normalizeIp(remoteAddr);
        }

        // 检查是否为本地开发环境
        if (isLocalhost(remoteAddr)) {
            log.debug("本地开发环境，RemoteAddr: {}", remoteAddr);
            return LOCALHOST_IPV4;
        }

        // 验证 CF-Connecting-IP 头
        if (cfIp == null || cfIp.trim().isEmpty()) {
            String cfRay = request.getHeader(CF_RAY);
            log.warn("拒绝非 Cloudflare 请求 - RemoteAddr: {}, CF-Connecting-IP: {}, CF-Ray: {}",
                     remoteAddr, cfIp, cfRay);
            throw new InvalidRequestException("非 Cloudflare 请求，拒绝访问");
        }

        log.debug("Cloudflare 请求 - CF-Connecting-IP: {}, RemoteAddr: {}", cfIp, remoteAddr);
        return cfIp.trim();
    }

    /**
     * 判断是否为本地请求
     */
    private static boolean isLocalhost(String ip) {
        return LOCALHOST_IPV4.equals(ip) || LOCALHOST_IPV6.equals(ip);
    }

    /**
     * 标准化 IP 地址（将 IPv6 localhost 转换为 IPv4）
     */
    private static String normalizeIp(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return LOCALHOST_IPV4;
        }
        return LOCALHOST_IPV6.equals(ip) ? LOCALHOST_IPV4 : ip.trim();
    }

    /**
     * 非 Cloudflare 请求异常
     */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }
}
