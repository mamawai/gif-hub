package com.mawai.ghweixin.service;

import com.mawai.ghweixin.dto.ProxyCheckResponse;

/**
 * IP 代理检测服务接口
 */
public interface ProxyCheckService {

    /**
     * 检测 IP（带缓存）
     *
     * @param ip IP地址
     * @return 检测结果
     */
    ProxyCheckResponse checkIp(String ip);
}
