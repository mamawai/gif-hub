package com.mawai.ghweixin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghmbplus.dao.LinuxdoInviteCodeMapper;
import com.mawai.ghmbplus.model.LinuxdoInviteCode;
import com.mawai.ghweixin.service.LinuxDoInviteService;
import com.mawai.ghweixin.strategy.EmailStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LinuxDoInviteServiceImpl implements LinuxDoInviteService {

    private final LinuxdoInviteCodeMapper inviteCodeMapper;
    private final CacheService cacheService;
    private final EmailStrategy emailStrategy;

    private static final String TOKEN_KEY_PREFIX = "LINUXDO_INVITE_TOKEN:";
    private static final String PENDING_EMAIL_PREFIX = "LINUXDO_INVITE_PENDING:";
    private static final String PENDING_DB_PREFIX = "pending:";
    private static final String APPLY_LOCK_KEY = "LINUXDO_INVITE_APPLY_LOCK";
    private static final long TOKEN_EXPIRE_MINUTES = 30;
    private static final long LOCK_TIMEOUT_SECONDS = 5;
    private static final String FRONT_URL = "https://weixin.mynnmy.top/linuxdo/invite/index.html";
    private static final Pattern EDU_EMAIL_PATTERN = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.edu\\.cn$", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean hasStock() {
        Long count = inviteCodeMapper.selectCount(
                new LambdaQueryWrapper<LinuxdoInviteCode>().isNull(LinuxdoInviteCode::getEmail)
        );
        return count != null && count > 0;
    }

    @Override
    public void apply(String email) {
        // 1. 校验日期单双数（使用中国时区）
        int dayOfMonth = LocalDate.now(ZoneId.of("Asia/Shanghai")).getDayOfMonth();
        if (dayOfMonth % 2 == 0) {
            throw new RuntimeException("今日暂停申请，请明日再来");
        }

        // 2. 校验邮箱格式
        if (email == null || !EDU_EMAIL_PATTERN.matcher(email).matches()) {
            throw new RuntimeException("仅支持 edu.cn 邮箱");
        }

        // 3. 校验是否已领取
        Long claimed = inviteCodeMapper.selectCount(
                new LambdaQueryWrapper<LinuxdoInviteCode>()
                        .eq(LinuxdoInviteCode::getEmail, email)
        );
        if (claimed != null && claimed > 0) {
            throw new RuntimeException("该邮箱已领取过邀请码");
        }

        // 4. 校验是否有 pending 申请
        String pendingKey = PENDING_EMAIL_PREFIX + email;
        if (cacheService.get(pendingKey) != null) {
            throw new RuntimeException("您已申请过，请查收邮件");
        }

        // 5. 加锁（防并发）
        boolean locked = cacheService.setIfAbsent(APPLY_LOCK_KEY, "1", LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!locked) {
            throw new RuntimeException("系统繁忙，请稍后重试");
        }
        try {
            // 6. 查找可用邀请码并预分配
            LinuxdoInviteCode code = inviteCodeMapper.selectOne(
                    new LambdaQueryWrapper<LinuxdoInviteCode>()
                            .isNull(LinuxdoInviteCode::getEmail)
                            .last("LIMIT 1")
            );
            if (code == null) {
                throw new RuntimeException("邀请码已发完，请关注后续活动");
            }

            // 7. 生成 token
            String token = UUID.randomUUID().toString().replace("-", "");

            // 8. 预分配邀请码（数据库设置 pending 状态）
            int updated = inviteCodeMapper.update(
                    new LambdaUpdateWrapper<LinuxdoInviteCode>()
                            .eq(LinuxdoInviteCode::getId, code.getId())
                            .isNull(LinuxdoInviteCode::getEmail)
                            .set(LinuxdoInviteCode::getEmail, PENDING_DB_PREFIX + token)
            );
            if (updated == 0) {
                throw new RuntimeException("系统繁忙，请稍后重试");
            }

            // 9. Redis 存储 token → email 和 email → token
            cacheService.set(TOKEN_KEY_PREFIX + token, email, TOKEN_EXPIRE_MINUTES, TimeUnit.MINUTES);
            cacheService.set(pendingKey, token, TOKEN_EXPIRE_MINUTES, TimeUnit.MINUTES);

            // 10. 异步发送邮件
            sendInviteEmailAsync(email, token);

            log.info("LinuxDo邀请码申请: email={}, token={}, codeId={}", email, token, code.getId());
        } finally {
            cacheService.delete(APPLY_LOCK_KEY);
        }
    }

    @Override
    public String verify(String token) {
        // 1. 验证 token
        String tokenKey = TOKEN_KEY_PREFIX + token;
        String email = cacheService.get(tokenKey);
        if (email == null) {
            throw new RuntimeException("链接已失效或无效，请重新申请");
        }

        // 2. 查找预分配的邀请码
        LinuxdoInviteCode code = inviteCodeMapper.selectOne(
                new LambdaQueryWrapper<LinuxdoInviteCode>()
                        .eq(LinuxdoInviteCode::getEmail, PENDING_DB_PREFIX + token)
        );
        if (code == null) {
            throw new RuntimeException("邀请码不存在，请重新申请");
        }

        // 3. 确认领取（把 pending:token 改成真实邮箱）
        int updated = inviteCodeMapper.update(
                new LambdaUpdateWrapper<LinuxdoInviteCode>()
                        .eq(LinuxdoInviteCode::getId, code.getId())
                        .eq(LinuxdoInviteCode::getEmail, PENDING_DB_PREFIX + token)
                        .set(LinuxdoInviteCode::getEmail, email)
                        .set(LinuxdoInviteCode::getClaimedAt, LocalDateTime.now())
        );
        if (updated == 0) {
            throw new RuntimeException("领取失败，请重试");
        }

        // 4. 删除 Redis 记录
        cacheService.delete(tokenKey);
        cacheService.delete(PENDING_EMAIL_PREFIX + email);

        log.info("LinuxDo邀请码领取成功: email={}, code={}", email, code.getCode());
        return code.getCode();
    }

    /**
     * 定时清理过期的 pending 邀请码（每3小时执行）
     */
    @Scheduled(fixedRate = 3 * 60 * 60 * 1000)
    public void cleanExpiredPending() {
        List<LinuxdoInviteCode> pendingCodes = inviteCodeMapper.selectList(
                new LambdaQueryWrapper<LinuxdoInviteCode>()
                        .likeRight(LinuxdoInviteCode::getEmail, PENDING_DB_PREFIX)
        );

        for (LinuxdoInviteCode code : pendingCodes) {
            String pendingEmail = code.getEmail();
            String token = pendingEmail.substring(PENDING_DB_PREFIX.length());

            // 检查 token 是否还在 Redis 中
            if (cacheService.get(TOKEN_KEY_PREFIX + token) == null) {
                // token 已过期，释放邀请码
                inviteCodeMapper.update(
                        new LambdaUpdateWrapper<LinuxdoInviteCode>()
                                .eq(LinuxdoInviteCode::getId, code.getId())
                                .eq(LinuxdoInviteCode::getEmail, pendingEmail)
                                .set(LinuxdoInviteCode::getEmail, null)
                );
                log.info("清理过期pending邀请码: codeId={}, token={}", code.getId(), token);
            }
        }
    }

    @Async("wxVirtualExecutor")
    public void sendInviteEmailAsync(String email, String token) {
        String verifyUrl = FRONT_URL + "?token=" + token;
        String subject = "LinuxDo 邀请码领取";
        String content = "您好！\n\n" +
                "请点击以下链接领取您的 LinuxDo 邀请码：\n\n" +
                verifyUrl + "\n\n" +
                "链接有效期 " + TOKEN_EXPIRE_MINUTES + " 分钟，请尽快领取。\n\n" +
                "如非本人操作，请忽略此邮件。";

        try {
            emailStrategy.useResendEmailService(email, subject, content);
            log.info("LinuxDo邀请邮件发送成功: email={}", email);
        } catch (Exception e) {
            log.error("LinuxDo邀请邮件发送失败: email={}, error={}", email, e.getMessage(), e);
        }
    }
}
