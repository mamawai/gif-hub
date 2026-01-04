package com.mawai.ghweixin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mawai.ghmbplus.dao.UserMapper;
import com.mawai.ghmbplus.dao.WechatUserMapper;
import com.mawai.ghmbplus.model.User;
import com.mawai.ghmbplus.model.WechatUser;
import com.mawai.ghweixin.service.WeChatAuthService;
import com.mawai.ghweixin.vo.LoginResultVO;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class WeChatAuthServiceImpl implements WeChatAuthService {

    private final UserMapper userMapper;
    private final WechatUserMapper wechatUserMapper;
    private final RestTemplate restTemplate;

    public WeChatAuthServiceImpl(
            UserMapper userMapper,
            WechatUserMapper wechatUserMapper,
            @Qualifier("weChatRestTemplate") RestTemplate restTemplate) {
        this.userMapper = userMapper;
        this.wechatUserMapper = wechatUserMapper;
        this.restTemplate = restTemplate;
    }

    @Value("${wechat.appId}")
    private String WECHAT_APP_ID;

    @Value("${wechat.secret}")
    private String WECHAT_SECRET;

    @Value("${wechat.grantType}")
    private String GRANT_TYPE;

    @Value("${wechat.loginUrl}")
    private String WECHAT_LOGIN_URL;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResultVO loginWithWeChat(String code) {
        String openId = getOpenId(code);

        // 1. 查询或创建 wechat_user
        QueryWrapper<WechatUser> wechatQuery = new QueryWrapper<>();
        wechatQuery.eq("open_id", openId);
        WechatUser wechatUser = wechatUserMapper.selectOne(wechatQuery);

        if (wechatUser == null) {
            // 创建新的微信用户记录
            wechatUser = new WechatUser()
                    .setOpenId(openId)
                    .setCreatedAt(LocalDateTime.now())
                    .setUpdatedAt(LocalDateTime.now());
            wechatUserMapper.insert(wechatUser);
            log.info("创建新的微信用户: wechatUserId={}, openId={}", wechatUser.getId(), openId);
        }

        // 2. 查询是否已绑定 user
        QueryWrapper<User> userQuery = new QueryWrapper<>();
        userQuery.eq("wechat_user_id", wechatUser.getId());
        List<User> users = userMapper.selectList(userQuery); // 只检查有没有账户查到一个就行

        if (users.isEmpty()) {
            // #表示仅通过微信登录的临时登录标识，后续会替换为用户ID
            StpUtil.login("#" + wechatUser.getId());
            StpUtil.getSession().set("loginType", "wechat_only");

            log.info("微信用户未绑定邮箱: wechatUserId={}", wechatUser.getId());
            return LoginResultVO.builder()
                    .token(StpUtil.getTokenValue())
                    .build();
        }

        // 3. 已绑定账号，检查 last_login_user_id
        if (users.size() == 1) {
            // 只有一个账户，直接登录并更新 last_login_user_id
            User user = users.getFirst();

            // 更新 last_login_user_id
            if (!user.getId().equals(wechatUser.getLastLoginUserId())) {
                wechatUser.setLastLoginUserId(user.getId());
                wechatUser.setUpdatedAt(LocalDateTime.now());
                wechatUserMapper.updateById(wechatUser);
                log.info("更新微信用户最后登录账号: wechatUserId={}, lastLoginUserId={}", wechatUser.getId(), user.getId());
            }

            StpUtil.login(user.getId());
            StpUtil.getSession().set("loginType", "full");
            log.info("微信登录成功: userId={}, wechatUserId={}", user.getId(), wechatUser.getId());

        } else {
            // 绑定了多个账号
            log.info("微信用户已绑定多个账号: wechatUserId={}, 账号数量={}", wechatUser.getId(), users.size());

            // 检查 last_login_user_id 是否存在且有效
            Long lastLoginUserId = wechatUser.getLastLoginUserId();
            if (lastLoginUserId != null) {
                // 验证 last_login_user_id 是否在绑定列表中
                User lastLoginUser = users.stream()
                        .filter(u -> u.getId().equals(lastLoginUserId))
                        .findFirst()
                        .orElse(null);

                if (lastLoginUser != null) {
                    // 自动登录到上次登录的账号
                    StpUtil.login(lastLoginUser.getId());
                    StpUtil.getSession().set("loginType", "full");
                    log.info("微信登录成功(使用上次登录账号): userId={}, wechatUserId={}", lastLoginUser.getId(), wechatUser.getId());

                    return LoginResultVO.builder()
                            .token(StpUtil.getTokenValue())
                            .build();
                }
            }

            // 没有 last_login_user_id 或无效，返回临时 token，需要用户通过邮箱登录一次
            StpUtil.login("#" + wechatUser.getId());
            StpUtil.getSession().set("loginType", "wechat_only");
            StpUtil.getSession().set("wechatUserId", wechatUser.getId());

            log.info("微信用户需要通过邮箱登录设置默认账号: wechatUserId={}", wechatUser.getId());
        }
        return LoginResultVO.builder()
                .token(StpUtil.getTokenValue())
                .build();
    }

    /**
     * 通过微信授权code获取openId
     *
     * @param code 微信授权code
     * @return 用户openId
     */
    private String getOpenId(String code) {
        String url = UriComponentsBuilder.fromUriString(WECHAT_LOGIN_URL)
                .queryParam("appid", WECHAT_APP_ID)
                .queryParam("secret", WECHAT_SECRET)
                .queryParam("js_code", code)
                .queryParam("grant_type", GRANT_TYPE)
                .toUriString();

        String result = restTemplate.getForObject(url, String.class);
        JSONObject jsonObject = JSONUtil.parseObj(result);
        log.info("微信登录结果jsonObject: {}", jsonObject);
        return jsonObject.getStr("openid");
    }
}
