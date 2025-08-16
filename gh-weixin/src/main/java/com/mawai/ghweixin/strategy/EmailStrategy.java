package com.mawai.ghweixin.strategy;

public interface EmailStrategy {

    /**
     * 使用resend 发送邮件
     */
    void useResendEmailService(String targetEmail, String subject, String content);

    /**
     * 使用网易云邮箱 发送邮件
     */
    public void use163EmailService();

    /**
     * 使用QQ邮箱 发送邮件
     */
    public void useQQEmailService();

    /**
     * 使用阿里云邮箱 发送邮件
     */
    public void useAliYunEmailService();
}
