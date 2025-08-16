package com.mawai.ghweixin.service;

import com.mawai.ghweixin.vo.LoginResultVO;
import com.mawai.ghweixin.dto.UserInfoDTO;

/**
 * 邮箱认证服务接口
 */
public interface EmailAuthService {
    
    /**
     * 发送验证码到指定邮箱
     * 
     * @param email 邮箱地址
     * @return 是否发送成功
     */
    boolean sendVerificationCode(String email);
    
    /**
     * 注册用户（通过邮箱）
     * 
     * @param email 邮箱
     * @param password 密码
     * @param verificationCode 验证码
     * @param nickname 昵称
     */
    void register(String email, String password, String verificationCode, String nickname);
    
    /**
     * 邮箱密码登录
     * 
     * @param email 邮箱
     * @param password 密码
     * @return 登录结果
     */
    Boolean loginByPassword(String email, String password);
    
    /**
     * 邮箱验证码登录/注册
     * 
     * @param email 邮箱
     * @param verificationCode 验证码
     * @return 登录结果
     */
    Boolean loginByCode(String email, String verificationCode);

    /**
     * 检查token并获取用户信息
     * 
     * @return 用户信息
     */
    UserInfoDTO checkAndGet();

    /**
     * 退出登录
     *
     * @return 是否退出成功
     */
    boolean logout();

    /**
     * 获取邮箱验证状态
     *
     * @return 邮箱验证状态
     */
    boolean isEmailVerified();
} 