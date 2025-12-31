package com.mawai.ghweixin.service;

/**
 * 注册限制服务
 * 用于防止恶意批量注册
 */
public interface RegistrationLimitService {

    /**
     * 检查 IP 是否允许注册（1小时内最多3个账号）
     *
     * @param ip IP 地址
     * @return true-允许注册，false-超过限制
     */
    boolean isIpAllowedToRegister(String ip);

    /**
     * 检查 IP+指纹组合是否允许注册（24小时内只能1个账号）
     *
     * @param ip IP 地址
     * @param fingerprint 浏览器指纹
     * @return true-允许注册，false-超过限制
     */
    boolean isIpFingerprintAllowedToRegister(String ip, String fingerprint);

    /**
     * 记录注册（IP）
     *
     * @param ip IP 地址
     */
    void recordIpRegistration(String ip);

    /**
     * 记录注册（IP+指纹）
     *
     * @param ip IP 地址
     * @param fingerprint 浏览器指纹
     */
    void recordIpFingerprintRegistration(String ip, String fingerprint);
}
