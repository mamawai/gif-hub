package com.mawai.ghweixin.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * ProxyCheck.io API 响应 DTO
 * <p>
 * API 文档: <a href="https://proxycheck.io/api/">https://proxycheck.io/api/</a>
 * <p>
 * 响应格式:
 * - status: ok/warning/denied/error
 * - ok/warning: 有 "{ip}": {...} 字段
 * - denied/error: 只有 status 和 message
 */
@Data
@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProxyCheckResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 状态: "ok", "warning", "denied", "error"
     */
    private String status;

    /**
     * 错误或警告信息（仅 denied/error 时存在）
     */
    private String message;

    /**
     * 查询耗时（毫秒）
     */
    @JsonProperty("query_time")
    private Integer queryTime;

    /**
     * IP 检测结果（动态字段，ok/warning 时存在）
     */
    private Map<String, IpDetails> ipResults = new HashMap<>();

    /**
     * 捕获动态 IP 字段
     */
    @JsonAnySetter
    public void setIpResult(String key, Object value) {
        // 忽略已知字段
        if (!"status".equals(key) && !"message".equals(key) && !"query_time".equals(key) && value instanceof Map) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> valueMap = (Map<String, Object>) value;
                IpDetails details = mapToIpDetails(valueMap);
                ipResults.put(key, details);
            } catch (Exception e) {
                log.error("IP字段转换失败: key={}, error={}", key, e.getMessage());
            }
        }
    }

    /**
     * 手动映射 Map 到 IpDetails
     */
    @SuppressWarnings("unchecked")
    private IpDetails mapToIpDetails(Map<String, Object> map) {
        IpDetails details = new IpDetails();

        // 映射 detections
        if (map.containsKey("detections") && map.get("detections") instanceof Map) {
            Map<String, Object> detectionsMap = (Map<String, Object>) map.get("detections");
            Detections detections = new Detections();
            detections.setProxy(Boolean.TRUE.equals(detectionsMap.get("proxy")));
            detections.setVpn(Boolean.TRUE.equals(detectionsMap.get("vpn")));
            detections.setCompromised(Boolean.TRUE.equals(detectionsMap.get("compromised")));
            detections.setScraper(Boolean.TRUE.equals(detectionsMap.get("scraper")));
            detections.setTor(Boolean.TRUE.equals(detectionsMap.get("tor")));
            detections.setHosting(Boolean.TRUE.equals(detectionsMap.get("hosting")));
            detections.setAnonymous(Boolean.TRUE.equals(detectionsMap.get("anonymous")));

            // 映射 risk 和 confidence
            if (detectionsMap.containsKey("risk")) {
                detections.setRisk(((Number) detectionsMap.get("risk")).intValue());
            }
            if (detectionsMap.containsKey("confidence")) {
                detections.setConfidence(((Number) detectionsMap.get("confidence")).intValue());
            }

            details.setDetections(detections);
        }

        // 映射 last_updated
        if (map.containsKey("last_updated")) {
            details.setLastUpdated((String) map.get("last_updated"));
        }

        return details;
    }

    /**
     * 获取第一个 IP 的检测结果
     */
    public IpDetails getFirstIpDetails() {
        return ipResults.isEmpty() ? null : ipResults.values().iterator().next();
    }

    /**
     * 是否检测成功（包含检测数据）
     */
    public boolean isSuccess() {
        return ("ok".equals(status) || "warning".equals(status)) && !ipResults.isEmpty();
    }

    /**
     * 是否为错误响应
     */
    public boolean isError() {
        return "error".equals(status);
    }

    /**
     * 是否被拒绝（配额用尽）
     */
    public boolean isDenied() {
        return "denied".equals(status);
    }

    /**
     * IP 详细信息
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IpDetails implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 检测结果
         */
        private Detections detections;

        /**
         * 最后更新时间
         */
        @JsonProperty("last_updated")
        private String lastUpdated;
    }

    /**
     * 检测结果
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Detections implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 是否为代理
         */
        private boolean proxy;

        /**
         * 是否为 VPN
         */
        private boolean vpn;

        /**
         * 是否为被攻陷的主机
         */
        private boolean compromised;

        /**
         * 是否为爬虫
         */
        private boolean scraper;

        /**
         * 是否为 Tor 节点
         */
        private boolean tor;

        /**
         * 是否为托管服务器
         */
        private boolean hosting;

        /**
         * 是否为匿名代理
         */
        private boolean anonymous;

        /**
         * 风险分数 (0-100)
         */
        private int risk;

        /**
         * 置信度 (0-100)
         */
        private int confidence;
    }
}
