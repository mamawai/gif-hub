package com.mawai.ghweixin.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.ghmbplus.dao.UserCategoryMapper;
import com.mawai.ghmbplus.dao.UserMapper;
import com.mawai.ghmbplus.model.User;
import com.mawai.ghmbplus.model.UserCategory;
import com.mawai.ghweixin.config.LinuxDoConfig;
import com.mawai.ghweixin.dto.LinuxDoUserInfo;
import com.mawai.ghweixin.service.LinuxDoOAuthService;
import com.mawai.ghweixin.vo.LoginResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
public class LinuxDoOAuthServiceImpl implements LinuxDoOAuthService {

    private final UserMapper userMapper;
    private final UserCategoryMapper userCategoryMapper;
    private final RestTemplate restTemplate;
    private final LinuxDoConfig config;

    public LinuxDoOAuthServiceImpl(
            UserMapper userMapper,
            UserCategoryMapper userCategoryMapper,
            LinuxDoConfig config,
            @Qualifier("linuxDoRestTemplate") RestTemplate restTemplate) {
        this.userMapper = userMapper;
        this.userCategoryMapper = userCategoryMapper;
        this.config = config;
        this.restTemplate = restTemplate;
    }

    private static final String TOKEN_URL = "https://connect.linux.do/oauth2/token";
    private static final String USER_INFO_URL = "https://connect.linux.do/api/user";

    @Override
    public LoginResultVO handleCallback(String code, String fingerprint, String clientIp) {
        // 1. 用 code 换取 access_token
        String accessToken = exchangeCodeForToken(code);

        // 2. 获取用户信息
        LinuxDoUserInfo userInfo = getUserInfo(accessToken);
        log.info("LinuxDo用户信息: id={}, username={}", userInfo.getId(), userInfo.getUsername());

        // 3. 查找或创建用户（用 wechatUserId 存储 LinuxDo ID，负数区分微信用户）
        Long linuxdoId = -userInfo.getId();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getWechatUserId, linuxdoId)
        );

        if (user == null) {
            user = new User();
            user.setWechatUserId(linuxdoId);
            user.setNickname(userInfo.getUsername().isBlank() ? userInfo.getName() : userInfo.getUsername());
            user.setEmail(null);
            user.setPassword(null);
            user.setEmailVerified((byte) 1);
            user.setStatus((byte) 1);
            user.setRegisterIp(clientIp);
            user.setRegisterFingerprint(fingerprint);
            user.setCreatedAt(LocalDateTime.now());
            userMapper.insert(user);

            userCategoryMapper.insert(new UserCategory().setUserId(user.getId()).setCategoryName("默认"));

            log.info("LinuxDo新用户注册: userId={}, nickname={}", user.getId(), user.getNickname());
        } else {
            log.info("LinuxDo用户登录: userId={}, nickname={}", user.getId(), user.getNickname());
        }

        // 4. Sa-Token 登录
        StpUtil.login(user.getId());

        return new LoginResultVO(StpUtil.getTokenValue());
    }

    private String exchangeCodeForToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("code", code);
        params.add("client_id", config.getClientId());
        params.add("client_secret", config.getClientSecret());
        params.add("redirect_uri", config.getRedirectUri());

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

        try {
            var response = restTemplate.postForEntity(TOKEN_URL, request, Map.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String accessToken = (String) response.getBody().get("access_token");
                if (accessToken == null) {
                    log.error("LinuxDo token响应无access_token: {}", response.getBody());
                    throw new RuntimeException("获取access_token失败");
                }
                return accessToken;
            }
            throw new RuntimeException("获取access_token失败: " + response.getStatusCode());
        } catch (Exception e) {
            log.error("LinuxDo换取token失败: {}", e.getMessage(), e);
            throw new RuntimeException("LinuxDo授权失败: " + e.getMessage());
        }
    }

    private LinuxDoUserInfo getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<LinuxDoUserInfo> response = restTemplate.exchange(
                    USER_INFO_URL, HttpMethod.GET, request, LinuxDoUserInfo.class
            );
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody();
            }
            throw new RuntimeException("获取用户信息失败: " + response.getStatusCode());
        } catch (Exception e) {
            log.error("LinuxDo获取用户信息失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取LinuxDo用户信息失败: " + e.getMessage());
        }
    }
}
