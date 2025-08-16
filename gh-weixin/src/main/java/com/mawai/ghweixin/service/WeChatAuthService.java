package com.mawai.ghweixin.service;

import com.mawai.ghweixin.vo.LoginResultVO;

public interface WeChatAuthService {
    LoginResultVO loginWithWeChat(String code);
}
