package com.mawai.ghweixin.service;

import com.mawai.ghweixin.vo.LoginResultVO;
import com.mawai.ghweixin.vo.UserInfoVO;

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
     * Web端发送验证码
     *
     * @param email 邮箱地址
     * @return 是否发送成功
     */
    boolean sendWebVerificationCode(String email);
    
    /**
     * Web端登录（支持密码和验证码两种方式）
     *
     * @param email 邮箱
     * @param password 密码（密码登录时传入）
     * @param verificationCode 验证码（验证码登录时传入）
     * @return 登录结果（包含token）
     */
    LoginResultVO webLogin(String email, String password, String verificationCode);
    
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
    UserInfoVO checkAndGet();

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

    /**
     * 注销账号（硬删除）
     *
     * @param password 用户密码（用于验证）
     * @return 是否注销成功
     */
    boolean deleteAccount(String password);
}