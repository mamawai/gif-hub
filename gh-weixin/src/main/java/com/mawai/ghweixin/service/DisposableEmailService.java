package com.mawai.ghweixin.service;

/**
 * 临时邮箱检测服务接口
 *
 * 检测邮箱是否为临时邮箱（一次性邮箱）
 */
public interface DisposableEmailService {

    /**
     * 检测邮箱是否为临时邮箱
     *
     * @param email 邮箱地址
     * @return true=临时邮箱, false=正常邮箱
     */
    boolean isDisposableEmail(String email);

    /**
     * 刷新临时邮箱域名列表
     * 从 GitHub 下载最新的域名列表
     *
     * @return 刷新是否成功
     */
    boolean refreshDisposableDomains();
}
