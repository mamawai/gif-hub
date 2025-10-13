package com.mawai.ghweixin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mawai.ghmbplus.dao.UserMapper;
import com.mawai.ghmbplus.model.User;
import com.mawai.ghweixin.service.WeChatAuthService;
import com.mawai.ghweixin.utils.HttpClientUtil;
import com.mawai.ghweixin.vo.LoginResultVO;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class WeChatAuthServiceImpl implements WeChatAuthService {

    protected final UserMapper userMapper;

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
        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("open_id", openId);
        User user = userMapper.selectOne(queryWrapper);

        if (user == null) {
            // 用户不存在，创建新用户
            user = new User();
            user.setOpenId(openId);
            user.setStatus((byte) 1);
            user.setEmailVerified((byte) 0);
            user.setCreatedAt(LocalDateTime.now());
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.insert(user);
        }

        StpUtil.login(user.getId());
        // 用户是否邮箱验证过
        StpUtil.getSession().set("emailAuth", user.getEmailVerified() == 1 ? "full" : "basic");

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
        HashMap<String, String> params = new HashMap<>();
        params.put("appid", WECHAT_APP_ID);
        params.put("secret", WECHAT_SECRET);
        params.put("js_code", code);
        params.put("grant_type", GRANT_TYPE);
        String result = HttpClientUtil.doGet(WECHAT_LOGIN_URL, params);
        JSONObject jsonObject = JSONUtil.parseObj(result);
        log.info("微信登录结果jsonObject: {}", jsonObject);
        return jsonObject.getStr("openid");
    }
}
