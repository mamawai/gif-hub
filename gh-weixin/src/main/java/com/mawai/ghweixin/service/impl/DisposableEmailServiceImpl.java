package com.mawai.ghweixin.service.impl;

import com.mawai.ghweixin.service.DisposableEmailService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * 临时邮箱检测服务实现
 * <p>
 * 数据来源: <a href="https://github.com/disposable/disposable-email-domains">https://github.com/disposable/disposable-email-domains</a>
 * 每天自动更新临时邮箱域名列表
 * <p>
 * 使用说明:
 * 1. 启动时自动加载临时邮箱域名列表
 * 2. 每天凌晨 3 点自动更新列表
 * 3. 支持手动刷新列表
 */
@Service
@Slf4j
public class DisposableEmailServiceImpl implements DisposableEmailService {

    /**
     * GitHub 开源临时邮箱域名列表（每日更新）
     * 格式: 纯文本，一行一个域名
     */
    private static final String DISPOSABLE_DOMAINS_URL =
            "https://disposable.github.io/disposable-email-domains/domains.txt";

    /**
     * 临时邮箱域名集合
     */
    private Set<String> disposableDomains = new HashSet<>();

    private final RestTemplate restTemplate;
    public DisposableEmailServiceImpl(
            @Qualifier("getIpIntelRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Value("${disposable-email.enabled}")
    private boolean enabled;

    @Value("${disposable-email.auto-update}")
    private boolean autoUpdate;

    /**
     * 应用启动时加载临时邮箱域名列表
     */
    @PostConstruct
    public void init() {
        if (enabled) {
            log.info("初始化临时邮箱检测服务...");
            boolean success = refreshDisposableDomains();
            if (success) {
                log.info("临时邮箱域名列表加载完成，共 {} 个域名", disposableDomains.size());
            } else {
                log.error("临时邮箱域名列表加载失败！将使用空列表（所有邮箱都允许）");
            }
        } else {
            log.info("临时邮箱检测已禁用");
        }
    }

    /**
     * 每天凌晨 3 点自动更新临时邮箱域名列表
     */
    @Scheduled(cron = "${disposable-email.update-cron}")
    public void scheduledRefresh() {
        if (enabled && autoUpdate) {
            log.info("开始定时更新临时邮箱域名列表...");
            refreshDisposableDomains();
        }
    }

    @Override
    public boolean isDisposableEmail(String email) {
        // 检查是否启用
        if (!enabled) return false;

        // 参数校验
        if (email == null || email.trim().isEmpty()) {
            log.warn("邮箱地址为空");
            return false;
        }

        // 提取域名
        String domain = extractDomain(email);
        if (domain == null) {
            log.warn("无效的邮箱地址格式: {}", email);
            return false;
        }

        // 检查是否在临时邮箱列表中
        boolean isDisposable = disposableDomains.contains(domain.toLowerCase());

        if (isDisposable) {
            log.warn("检测到临时邮箱: email={}, domain={}", email, domain);
        }

        return isDisposable;
    }

    @Override
    public boolean refreshDisposableDomains() {
        try {
            log.info("从 GitHub 下载临时邮箱域名列表: {}", DISPOSABLE_DOMAINS_URL);

            // 下载域名列表
            String response = restTemplate.getForObject(DISPOSABLE_DOMAINS_URL, String.class);

            if (response == null || response.trim().isEmpty()) {
                log.error("下载的临时邮箱域名列表为空");
                return false;
            }

            // 解析域名列表
            Set<String> newDomains = new HashSet<>();
            String[] lines = response.split("\\r?\\n");

            for (String line : lines) {
                String domain = line.trim().toLowerCase();
                if (!domain.isEmpty() && !domain.startsWith("#")) {
                    newDomains.add(domain);
                }
            }

            if (newDomains.isEmpty()) {
                log.error("解析临时邮箱域名列表失败，没有有效域名");
                return false;
            }

            // 整体替换
            disposableDomains = newDomains;

            log.info("临时邮箱域名列表更新成功，共 {} 个域名", disposableDomains.size());
            return true;

        } catch (Exception e) {
            log.error("刷新临时邮箱域名列表失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从邮箱地址提取域名
     *
     * @param email 邮箱地址
     * @return 域名，失败返回 null
     */
    private String extractDomain(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }

        String[] parts = email.split("@");
        if (parts.length != 2) {
            return null;
        }

        return parts[1].trim().toLowerCase();
    }
}
