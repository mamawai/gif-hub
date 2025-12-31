package com.mawai.ghweixin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * GetIPIntel API 响应 DTO
 * <p>
 * API 文档: <a href="https://getipintel.net/">https://getipintel.net/</a>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GetIpIntelResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 状态: "success" 或 "error"
     */
    private String status;

    /**
     * 代理概率分数 (0-1) 或错误码 (负数)
     * - 0.0-1.0: 代理概率，越高越可疑
     * - -1: 无效输入
     * - -2: 无效 IP 地址
     * - -3: 不可路由或私有地址
     * - -4: 数据库不可用
     * - -5: IP 被封禁或无权限
     * - -6: 缺少或无效的联系信息
     */
    private String result;

    /**
     * 错误信息（仅在 status=error 时存在）
     */
    private String message;

    /**
     * 查询的 IP 地址
     */
    private String queryIP;

    /**
     * 是否为恶意 IP (oflags=b)
     * "1" = 恶意IP, "0" = 正常
     */
    @JsonProperty("BadIP")
    private String badIP;

    /**
     * 国家代码 (oflags=c)
     * 例如: "US", "CN"
     */
    @JsonProperty("Country")
    private String country;

    /**
     * ASN 号 (oflags=a)
     * 网络运营商编号
     */
    @JsonProperty("ASN")
    private String asn;


    /**
     * 获取代理概率分数 (0-1)
     *
     * @return 代理概率，错误返回负数错误码
     */
    public double getProxyScore() {
        try {
            double score = Double.parseDouble(result);
            // 负数是错误码，直接返回
            if (score < 0) {
                return score;
            }
            return score;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 是否为恶意 IP
     */
    public boolean isBadIp() {
        return "1".equals(badIP);
    }

    /**
     * 是否检测成功
     */
    public boolean isSuccess() {
        return "success".equals(status);
    }

    /**
     * 是否为错误响应
     */
    public boolean isError() {
        return "error".equals(status);
    }
}
