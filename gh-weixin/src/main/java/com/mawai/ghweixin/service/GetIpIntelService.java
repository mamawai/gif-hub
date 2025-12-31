package com.mawai.ghweixin.service;

import com.mawai.ghweixin.dto.GetIpIntelResponse;

/**
 * IP 欺诈检测服务接口
 */
public interface GetIpIntelService {

    /**
     * 检测 IP（带缓存）
     */
    GetIpIntelResponse checkIp(String ip);
}
