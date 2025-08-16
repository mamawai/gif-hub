package com.mawai.ghweixin.strategy.impl;

import com.mawai.ghweixin.strategy.EmailStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EmailStrategyImpl implements EmailStrategy {

    /**
     * resend part
     */
    @Value("${resend.api-key}")
    private String resendApiKey;

    @Value("${resend.from-email}")
    private String resendFromEmail;

    @Override
    public void useResendEmailService(String targetEmail, String subject, String content) {
        Resend resend = new Resend(resendApiKey);
        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(resendFromEmail)
                .to(targetEmail)
                .subject(subject)
                .html("<strong>" + content + "</strong>")
                .build();
        try {
            CreateEmailResponse data = resend.emails().send(params);
            log.info("邮件发送成功: {}", data.getId());
        } catch (ResendException e) {
            log.error("邮件发送失败: {}", e.getMessage(), e);
        }
    }

    @Override
    public void use163EmailService() {
        throw new RuntimeException("暂不支持163邮箱服务");
    }

    @Override
    public void useQQEmailService() {
        throw new RuntimeException("暂不支持QQ邮箱服务");
    }

    @Override
    public void useAliYunEmailService() {
        throw new RuntimeException("暂不支持阿里云邮箱服务");
    }
}
