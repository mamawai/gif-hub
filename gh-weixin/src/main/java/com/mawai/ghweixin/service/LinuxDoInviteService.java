package com.mawai.ghweixin.service;

public interface LinuxDoInviteService {

    /**
     * 检查邀请码库存状态
     * @return true=有库存，false=无库存
     */
    boolean hasStock();

    /**
     * 申请邀请码（发送验证邮件）
     * @param email edu.cn邮箱
     */
    void apply(String email);

    /**
     * 验证token并领取邀请码
     * @param token 邮件中的token
     * @return 邀请码
     */
    String verify(String token);
}
