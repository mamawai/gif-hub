package com.mawai.ghweixin.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.service.UserNicknameCacheService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghmbplus.dao.UserCategoryMapper;
import com.mawai.ghmbplus.dao.UserMapper;
import com.mawai.ghmbplus.dao.WechatUserMapper;
import com.mawai.ghmbplus.model.User;
import com.mawai.ghmbplus.model.UserCategory;
import com.mawai.ghmbplus.model.WechatUser;
import com.mawai.ghweixin.dto.GetIpIntelResponse;
import com.mawai.ghweixin.service.DisposableEmailService;
import com.mawai.ghweixin.service.GetIpIntelService;
import com.mawai.ghweixin.service.RegistrationLimitService;
import com.mawai.ghweixin.utils.IpUtil;
import com.mawai.ghweixin.vo.UserInfoVO;
import com.mawai.ghweixin.event.AccountDeleteEvent;
import com.mawai.ghweixin.service.EmailAuthService;
import com.mawai.ghweixin.strategy.EmailStrategy;
import com.mawai.ghweixin.utils.PasswordEncoder;
import com.mawai.ghweixin.utils.RsaEncryptUtil;
import com.mawai.ghweixin.vo.LoginResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailAuthServiceImpl implements EmailAuthService {

    private final CacheService cacheService;
    private final UserNicknameCacheService userNicknameCacheService;
    private final UserMapper userMapper;
    private final WechatUserMapper wechatUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailStrategy emailStrategy;
    private final UserCategoryMapper userCategoryMapper;
    private final GetIpIntelService getIpIntelService;
    private final DisposableEmailService disposableEmailService;
    private final RegistrationLimitService registrationLimitService;

    @Value("${getipintel.threshold.reject}")
    private double thresholdReject;

    /**
     * 验证码在Redis中的前缀
     */
    private static final String CODE_KEY_PREFIX = "EMAIL_VERIFICATION_CODE:";

    /**
     * 重置密码验证码前缀
     */
    private static final String RESET_PASSWORD_CODE_PREFIX = "RESET_PASSWORD_CODE:";

    /**
     * 注册分布式锁前缀
     */
    private static final String REGISTER_LOCK_PREFIX = "EMAIL_REGISTER_LOCK:";
    
    /**
     * 注销分布式锁前缀
     */
    private static final String DELETE_ACCOUNT_LOCK_PREFIX = "DELETE_ACCOUNT_LOCK:";
    
    /**
     * 注销邮箱黑名单前缀
     */
    private static final String DELETED_EMAIL_BLACKLIST_PREFIX = "DELETED_EMAIL_BLACKLIST:";
    
    /**
     * 验证码有效期（分钟）
     */
    private static final long CODE_EXPIRE_MINUTES = 60;
    
    /**
     * 分布式锁超时时间（秒）
     */
    private static final long LOCK_TIMEOUT_SECONDS = 10;
    
    /**
     * 邮箱黑名单有效期（小时）
     */
    private static final long EMAIL_BLACKLIST_HOURS = 24;

    @Override
    public boolean sendVerificationCode(String email) {
        try {
            // 1. 检查邮箱是否在黑名单中（24小时内注销的邮箱）
            String blacklistKey = DELETED_EMAIL_BLACKLIST_PREFIX + email;
            if (cacheService.get(blacklistKey) != null) {
                throw new RuntimeException("该邮箱已注销，24小时内无法重新注册");
            }

            // 2. 检查是否为临时邮箱
            if (disposableEmailService.isDisposableEmail(email)) {
                throw new RuntimeException("不支持使用临时邮箱注册，请使用常规邮箱地址");
            }

            // 3. 获取当前登录状态
            String loginId = StpUtil.getLoginIdAsString();
            String loginType = (String) StpUtil.getSession().get("loginType");

            // 4. 处理不同的登录状态
            if (loginId.startsWith("#")) {
                // 4.1 wechat_only状态 - 提取wechatUserId
                Long wechatUserId = Long.parseLong(loginId.substring(1));

                // 检查该邮箱是否已绑定到当前微信用户
                QueryWrapper<User> emailQuery = new QueryWrapper<>();
                emailQuery.eq("email", email);
                User existingEmailUser = userMapper.selectOne(emailQuery);

                boolean isAlreadyBound = existingEmailUser != null && wechatUserId.equals(existingEmailUser.getWechatUserId());

                // 检查3个账号限制（如果不是已绑定的邮箱）
                if (!isAlreadyBound) {
                    List<User> boundUsers = getBoundUsersByWechatId(wechatUserId);
                    if (boundUsers.size() >= 3) {
                        throw new RuntimeException("您已绑定3个邮箱账号，无法绑定更多邮箱，若想绑定更多请先注销原有账户");
                    }
                }

            } else if ("full".equals(loginType)) {
                // 4.2 full状态 - 已绑定邮箱的用户（小程序场景下一定有wechatUserId）
                Long userId = StpUtil.getLoginIdAsLong();
                User currentUser = userMapper.selectById(userId);

                if (currentUser == null) {
                    throw new RuntimeException("用户不存在");
                }

                if (currentUser.getWechatUserId() == null) {
                    throw new RuntimeException("请使用Web端接口发送验证码");
                }

                // 检查邮箱是否正确（必须是已绑定的3个邮箱之一）
                List<User> boundUsers = getBoundUsersByWechatId(currentUser.getWechatUserId());
                boolean isValidEmail = boundUsers.stream()
                        .anyMatch(u -> email.equals(u.getEmail()));

                if (!isValidEmail) {
                    throw new RuntimeException("邮箱错误，请输入已绑定的邮箱地址");
                }
            } else {
                throw new RuntimeException("请先通过微信登录");
            }

            // 5. 校验redis中是否存在验证码
            String key = CODE_KEY_PREFIX + email;
            String storedCode = cacheService.get(key);
            if (storedCode != null) {
                throw new RuntimeException("验证码已发送，请稍后再试");
            }

            // 6. 生成6位随机数字验证码
            String verificationCode = generateVerificationCode();

            // 7. 异步发送邮件
            sendEmailAsync(email, verificationCode);

            // 8. 将验证码存入Redis，设置过期时间
            cacheService.set(key, verificationCode, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);

            return true;
        } catch (Exception e) {
            log.error("发送验证码邮件失败: {}", e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * 异步发送验证码邮件
     *
     * @param email 收件人邮箱
     * @param verificationCode 验证码
     */
    @Async("wxVirtualExecutor")
    public void sendEmailAsync(String email, String verificationCode) {

        String subject = "GIF-HUB 验证码";
        String content = "感谢您支持GIF-HUB！您的验证码是：" + verificationCode + "，" + CODE_EXPIRE_MINUTES + "分钟内有效。请勿泄露给他人！";
        try {
            emailStrategy.useResendEmailService(email, subject, content);
            log.info("验证码邮件发送成功: {}", email);
        } catch (Exception e) {
            log.error("异步发送验证码邮件失败: {}, 错误: {}", email, e.getMessage(), e);
        }
    }

    /**
     * 验证邮箱验证码是否有效
     *
     * @param email 邮箱
     * @param code 验证码
     * @return true-验证失败，false-验证成功
     */
    public boolean isVerifyFail(String email, String code) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(code)) {
            return true;
        }
        
        String key = CODE_KEY_PREFIX + email;
        // 从缓存中获取验证码   
        String storedCode = cacheService.get(key);
        
        if (storedCode == null) {
            return true;  // 验证码不存在或已过期
        }
        
        boolean isMatch = storedCode.equals(code);
        
        if (isMatch) {
            // 验证成功后删除验证码，防止重复使用
            cacheService.delete(key);
        }
        
        return !isMatch;
    }

    @Override
    public void register(String email, String password, String verificationCode, String nickname, String clientIp, String fingerprint) {
        // IP风险检测
        checkIpRisk(clientIp, email);

        // 获取当前登录的微信用户ID（从session中）
        Long wechatUserId = (Long) StpUtil.getSession().get("wechatUserId");
        String loginType = (String) StpUtil.getSession().get("loginType");

        if (wechatUserId == null || !"wechat_only".equals(loginType)) {
            throw new RuntimeException("请先通过微信登录");
        }

        // 验证验证码
        if (isVerifyFail(email, verificationCode)) {
            throw new RuntimeException("验证码错误或已过期");
        }

        // 创建分布式锁的key
        String lockKey = REGISTER_LOCK_PREFIX + email;
        boolean lockAcquired = false;

        try {
            // 尝试获取分布式锁
            lockAcquired = cacheService.setIfAbsent(
                lockKey,
                String.valueOf(System.currentTimeMillis()),
                LOCK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );

            if (!lockAcquired) {
                throw new RuntimeException("请勿重复注册");
            }

            // 在锁内执行事务 - 绑定微信用户到邮箱账号
            SpringUtils.getAopProxy(this).registerWithTransaction(email, password, nickname, wechatUserId, clientIp, fingerprint);

        } catch (Exception e) {
            log.error("小程序用户注册失败: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage());
        } finally {
            // 操作完成后释放锁
            if (lockAcquired) {
                cacheService.delete(lockKey);
                log.info("释放邮箱注册分布式锁: {}", email);
            }
        }
    }

    @Override
    public LoginResultVO webRegister(String email, String password, String verificationCode, String nickname, String clientIp, String fingerprint) {
        // IP风险检测
        checkIpRisk(clientIp, email);

        // 验证验证码
        if (isVerifyFail(email, verificationCode)) {
            throw new RuntimeException("验证码错误或已过期");
        }

        // 创建分布式锁的key
        String lockKey = REGISTER_LOCK_PREFIX + email;
        boolean lockAcquired = false;

        try {
            // 尝试获取分布式锁
            lockAcquired = cacheService.setIfAbsent(
                lockKey,
                String.valueOf(System.currentTimeMillis()),
                LOCK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );

            if (!lockAcquired) {
                throw new RuntimeException("请勿重复注册");
            }

            // 在锁内执行事务 - 创建新用户
            Long newUserId = SpringUtils.getAopProxy(this).webRegisterWithTransaction(email, password, nickname, clientIp, fingerprint);

            // 自动登录新用户
            StpUtil.login(newUserId);

            // 设置会话类型和邮箱认证状态
            StpUtil.getSession().set("loginType", "email_only");
            StpUtil.getSession().set("emailAuth", "full");

            log.info("Web端用户注册成功: email={}, userId={}", email, newUserId);

            return LoginResultVO.builder()
                    .token(StpUtil.getTokenValue())
                    .build();

        } catch (Exception e) {
            log.error("Web端用户注册失败: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage());
        } finally {
            // 操作完成后释放锁
            if (lockAcquired) {
                cacheService.delete(lockKey);
                log.info("释放Web端邮箱注册分布式锁: {}", email);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void registerWithTransaction(String email, String password, String nickname, Long wechatUserId, String clientIp, String fingerprint) throws Exception {
        // 验证wechatUserId是否有效
        WechatUser wechatUser = wechatUserMapper.selectById(wechatUserId);
        if (wechatUser == null) {
            throw new RuntimeException("微信用户不存在");
        }

        // 检查3个账号限制
        List<User> boundUsers = getBoundUsersByWechatId(wechatUserId);
        if (boundUsers.size() >= 3) {
            throw new RuntimeException("您已绑定3个邮箱账号，无法绑定更多邮箱");
        }

        // 检查邮箱是否已存在
        if (isEmailRegistered(email)) {
            throw new RuntimeException("该邮箱已注册");
        }

        // 公共验证和创建用户
        Long userId = createUserWithValidation(email, password, nickname, wechatUserId, clientIp, fingerprint);

        // 更新微信用户的 last_login_user_id
        wechatUser.setLastLoginUserId(userId);
        wechatUser.setUpdatedAt(LocalDateTime.now());
        wechatUserMapper.updateById(wechatUser);
        log.info("更新微信用户最后登录账号: wechatUserId={}, lastLoginUserId={}", wechatUserId, userId);

        // 更新登录状态：从 wechat_only 升级到 full
        StpUtil.login(userId);
        StpUtil.getSession().set("loginType", "full");
        StpUtil.getSession().set("emailAuth", "full");
    }

    /**
     * Web端注册事务方法（创建新用户）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long webRegisterWithTransaction(String email, String password, String nickname, String clientIp, String fingerprint) throws Exception {
        // 检查邮箱是否已注册
        if (isEmailRegistered(email)) {
            throw new RuntimeException("该邮箱已注册");
        }

        // 检查邮箱黑名单
        String blacklistKey = DELETED_EMAIL_BLACKLIST_PREFIX + email;
        if (cacheService.get(blacklistKey) != null) {
            throw new RuntimeException("该邮箱已注销，24小时内无法重新注册");
        }

        // 创建用户（Web端不绑定微信）
        return createUserWithValidation(email, password, nickname, null, clientIp, fingerprint);
    }

    /**
     * 检查邮箱是否已注册
     */
    private boolean isEmailRegistered(String email) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", email);
        return userMapper.selectOne(queryWrapper) != null;
    }

    /**
     * 公共用户创建逻辑（包含验证）
     */
    private Long createUserWithValidation(String email, String password, String nickname, Long wechatUserId, String clientIp, String fingerprint) throws Exception {
        // IP 注册数量限制
        if (!registrationLimitService.isIpAllowedToRegister(clientIp)) {
            log.warn("IP 注册次数超限: email={}, ip={}", email, clientIp);
            throw new RuntimeException("注册过于频繁，请稍后再试");
        }

        // IP+指纹组合限制
        if (!registrationLimitService.isIpFingerprintAllowedToRegister(clientIp, fingerprint)) {
            log.warn("IP+指纹组合注册次数超限: email={}, ip={}, fingerprint={}", email, clientIp, fingerprint);
            throw new RuntimeException("检测到异常注册行为，请稍后再试");
        }

        // 生成昵称
        if (!StringUtils.hasText(nickname)) {
            nickname = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        // 创建用户
        User newUser = new User();
        newUser.setEmail(email);
        newUser.setEmailVerified((byte) 1);
        newUser.setPassword(passwordEncoder.encode(RsaEncryptUtil.decrypt(password)));
        newUser.setNickname(nickname);
        newUser.setStatus((byte) 1);
        newUser.setWechatUserId(wechatUserId);
        newUser.setRegisterIp(clientIp);
        newUser.setRegisterFingerprint(fingerprint);
        newUser.setCreatedAt(LocalDateTime.now());
        newUser.setUpdatedAt(LocalDateTime.now());

        userMapper.insert(newUser);
        Long userId = newUser.getId();

        log.info("创建新用户: userId={}, email={}, wechatUserId={}", userId, email, wechatUserId);

        // 写入昵称缓存
        userNicknameCacheService.updateNickname(userId, nickname);

        // 创建默认分类
        userCategoryMapper.insert(new UserCategory().setUserId(userId).setCategoryName("默认"));

        // 记录注册
        registrationLimitService.recordIpRegistration(clientIp);
        registrationLimitService.recordIpFingerprintRegistration(clientIp, fingerprint);

        return userId;
    }

    /**
     * Web端发送验证码（支持注册和登录）
     */
    @Override
    public boolean sendWebVerificationCode(String email) {
        try {
            // 1. 检查邮箱是否在黑名单中（24小时内注销的邮箱）
            String blacklistKey = DELETED_EMAIL_BLACKLIST_PREFIX + email;
            if (cacheService.get(blacklistKey) != null) {
                throw new RuntimeException("该邮箱已注销，24小时内无法重新注册");
            }

            // 2. 检查是否为临时邮箱
            if (disposableEmailService.isDisposableEmail(email)) {
                throw new RuntimeException("不支持使用临时邮箱，请使用常规邮箱地址");
            }

            // 3. 校验redis中是否存在验证码
            String key = CODE_KEY_PREFIX + email;
            String storedCode = cacheService.get(key);
            if (storedCode != null) {
                throw new RuntimeException("验证码已发送，请稍后再试");
            }

            // 4. 生成6位随机数字验证码
            String verificationCode = generateVerificationCode();

            // 5. 异步发送邮件
            sendEmailAsync(email, verificationCode);

            // 6. 将验证码存入Redis，设置过期时间
            cacheService.set(key, verificationCode, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);

            return true;
        } catch (Exception e) {
            log.error("Web端发送验证码失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Web端登录（支持密码和验证码两种方式）
     */
    @Override
    public LoginResultVO webLogin(String email, String password, String verificationCode) {
        // 查询用户
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", email);
        User user = userMapper.selectOne(queryWrapper);

        if (user == null) {
            throw new RuntimeException("请先注册邮箱");
        }

        // 根据传入参数判断登录方式
        if (password != null) {
            // 密码登录
            try {
                if (!passwordEncoder.matches(RsaEncryptUtil.decrypt(password), user.getPassword())) {
                    throw new RuntimeException("密码错误");
                }
            } catch (Exception e) {
                log.error("密码验证失败: {}", e.getMessage(), e);
                throw new RuntimeException("密码错误");
            }
        } else if (verificationCode != null) {
            // 验证码登录
            if (isVerifyFail(email, verificationCode)) {
                throw new RuntimeException("验证码错误或已过期");
            }
        } else {
            throw new RuntimeException("请提供密码或验证码");
        }

        // 登录
        StpUtil.login(user.getId());

        // 设置会话类型
        String loginType = user.getWechatUserId() != null ? "full" : "email_only";
        StpUtil.getSession().set("loginType", loginType);
        StpUtil.getSession().set("emailAuth", "full");

        // 如果该用户绑定了微信，更新 wechat_user.last_login_user_id
        updateWechatUserLastLogin(user, "Web登录");

        return LoginResultVO.builder()
                .token(StpUtil.getTokenValue())
                .build();
    }

    /**
     * 邮箱密码登录 - 支持自动绑定已有账户
     */
    @Override
    public Boolean loginByPassword(String email, String password) {
        // 获取当前登录状态
        String loginId = StpUtil.getLoginIdAsString();
        String loginType = (String) StpUtil.getSession().get("loginType");

        User user;

        // 处理不同的登录状态
        if (loginId.startsWith("#")) {
            // wechat_only 状态 - 提取 wechatUserId
            Long wechatUserId = Long.parseLong(loginId.substring(1));

            // 查询该邮箱对应的用户（不限制 wechat_user_id）
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("email", email);
            user = userMapper.selectOne(queryWrapper);

            if (user == null) {
                // 邮箱不存在
                throw new RuntimeException("邮箱不存在");
            }

            // 验证密码
            try {
                if (!passwordEncoder.matches(RsaEncryptUtil.decrypt(password), user.getPassword())) {
                    throw new RuntimeException("密码错误");
                }
            } catch (Exception e) {
                log.error("密码验证失败: {}", e.getMessage(), e);
                throw new RuntimeException("密码错误");
            }

            // 密码验证成功，检查是否需要绑定
            if (!wechatUserId.equals(user.getWechatUserId())) {
                // 检查该邮箱是否已绑定到其他微信用户
                if (user.getWechatUserId() != null) {
                    throw new RuntimeException("该邮箱已绑定到其他微信账号");
                }

                // 检查当前微信用户已绑定的账号数量
                List<User> boundUsers = getBoundUsersByWechatId(wechatUserId);
                if (boundUsers.size() >= 3) {
                    throw new RuntimeException("当前微信用户已绑定3个邮箱账号，无法绑定更多邮箱");
                }

                // 绑定邮箱到当前微信用户
                user.setWechatUserId(wechatUserId);
                user.setUpdatedAt(LocalDateTime.now());
                userMapper.updateById(user);
                log.info("密码登录自动绑定: wechatUserId={}, userId={}, email={}", wechatUserId, user.getId(), email);
            }

            // 更新微信用户的 last_login_user_id
            WechatUser wechatUser = wechatUserMapper.selectById(wechatUserId);
            if (wechatUser != null) {
                wechatUser.setLastLoginUserId(user.getId());
                wechatUser.setUpdatedAt(LocalDateTime.now());
                wechatUserMapper.updateById(wechatUser);
            }

            // 升级登录状态
            StpUtil.login(user.getId());
            StpUtil.getSession().set("loginType", "full");
            StpUtil.getSession().set("emailAuth", "full");

        } else if ("full".equals(loginType)) {
            // full 状态 - 已绑定邮箱的用户
            Long userId = StpUtil.getLoginIdAsLong();
            user = userMapper.selectById(userId);

            if (user == null) {
                throw new RuntimeException("用户不存在");
            }

            if (user.getEmailVerified() == 0) {
                throw new RuntimeException("请先注册邮箱");
            }

            // 验证邮箱
            if (!user.getEmail().equals(email)) {
                throw new RuntimeException("邮箱错误");
            }

            // 验证密码
            try {
                if (!passwordEncoder.matches(RsaEncryptUtil.decrypt(password), user.getPassword())) {
                    throw new RuntimeException("密码错误");
                }
            } catch (Exception e) {
                log.error("密码验证失败: {}", e.getMessage(), e);
                throw new RuntimeException(e.getMessage());
            }

            // 设置邮箱认证状态
            StpUtil.getSession().set("emailAuth", "full");

            // 如果该用户绑定了微信，更新 wechat_user.last_login_user_id
            updateWechatUserLastLogin(user, "小程序密码登录");

        } else {
            throw new RuntimeException("请先通过微信登录");
        }

        // 返回登录结果
        return true;
    }

    @Override
    public Boolean loginByCode(String email, String verificationCode) {
        // 获取当前登录状态
        String loginId = StpUtil.getLoginIdAsString();
        String loginType = (String) StpUtil.getSession().get("loginType");

        User user;

        // 处理不同的登录状态
        if (loginId.startsWith("#")) {
            // wechat_only 状态 - 提取 wechatUserId
            Long wechatUserId = Long.parseLong(loginId.substring(1));

            // 查询该邮箱是否已绑定到当前微信用户
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("email", email);
            queryWrapper.eq("wechat_user_id", wechatUserId);
            user = userMapper.selectOne(queryWrapper);

            if (user == null) {
                // 邮箱未绑定到当前微信用户，需要注册
                throw new RuntimeException("请先注册邮箱");
            }

        } else if ("full".equals(loginType)) {
            // full 状态 - 已绑定邮箱的用户
            Long userId = StpUtil.getLoginIdAsLong();
            user = userMapper.selectById(userId);

            if (user == null) {
                throw new RuntimeException("用户不存在");
            }

            if (user.getEmailVerified() == 0) {
                throw new RuntimeException("请先注册邮箱");
            }

            // 验证邮箱
            if (!user.getEmail().equals(email)) {
                throw new RuntimeException("邮箱错误");
            }

        } else {
            throw new RuntimeException("请先通过微信登录");
        }

        // 验证验证码
        if (isVerifyFail(email, verificationCode)) {
            throw new RuntimeException("验证码错误或已过期");
        }

        // 设置邮箱认证状态
        StpUtil.getSession().set("emailAuth", "full");

        // 如果该用户绑定了微信，更新 wechat_user.last_login_user_id
        updateWechatUserLastLogin(user, "小程序验证码登录");

        log.info("验证码登录成功: email={}, userId={}", email, user.getId());

        // 返回登录结果
        return true;
    }
    
    /**
     * 生成6位随机数字验证码
     *
     * @return 6位数字验证码
     */
    private String generateVerificationCode() {
        Random random = new Random();
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            code.append(random.nextInt(10));
        }
        return code.toString();
    }

    /**
     * 检查token并获取用户信息
     */
    @Override
    public UserInfoVO checkAndGet() {
        // 检查是否已登录 -- check token
        if (!StpUtil.isLogin()) {
            throw new RuntimeException("未登录");
        } 
        
        // 获取当前登录用户ID
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        return UserInfoVO.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .createTime(user.getCreatedAt())
                .build();
    }

    /**
     * 退出登录
     */
    @Override
    public boolean logout() {
        try {
            // 退出登录
            StpUtil.logout();
            return true;
        } catch (Exception e) {
            log.error("退出登录失败: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean isEmailVerified() {
        return StpUtil.getSession().get("emailAuth").equals("full");
    }

    /**
     * 注销账号（硬删除）
     */
    @Override
    public boolean deleteAccount(String password) {
        // 验证用户登录状态
        if (!StpUtil.isLogin()) {
            throw new RuntimeException("未登录");
        }
        
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        
        // 验证邮箱是否已注册
        if (user.getEmailVerified() == 0) {
            throw new RuntimeException("邮箱未验证，无法注销");
        }
        
        String email = user.getEmail();
        
        // 验证密码
        try {
            if (!passwordEncoder.matches(RsaEncryptUtil.decrypt(password), user.getPassword())) {
                throw new RuntimeException("密码错误");
            }
        } catch (Exception e) {
            log.error("密码验证失败: {}", e.getMessage(), e);
            throw new RuntimeException("密码验证失败");
        }
        
        // 获取分布式锁
        String lockKey = DELETE_ACCOUNT_LOCK_PREFIX + userId;
        boolean lockAcquired = false;
        
        try {
            lockAcquired = cacheService.setIfAbsent(
                lockKey,
                String.valueOf(System.currentTimeMillis()),
                LOCK_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            );
            
            if (!lockAcquired) {
                throw new RuntimeException("操作过于频繁，请稍后再试");
            }
            
            log.info("开始注销账号: userId={}, email={}", userId, email);
            
            // 将邮箱加入黑名单（24小时）
            String blacklistKey = DELETED_EMAIL_BLACKLIST_PREFIX + email;
            cacheService.set(blacklistKey, String.valueOf(System.currentTimeMillis()), EMAIL_BLACKLIST_HOURS, TimeUnit.HOURS);
            log.info("邮箱已加入黑名单: {}", email);

            // 发布事件，后续处理交给异步任务
            SpringUtils.context().publishEvent(
                new AccountDeleteEvent().setUserId(userId).setEmail(email)
            );
            
            log.info("已发布账号注销事件: userId={}, email={}", userId, email);
            return true;
            
        } catch (Exception e) {
            log.error("注销账号失败: userId={}, error={}", userId, e.getMessage(), e);
            throw new RuntimeException("注销账号失败: " + e.getMessage());
        } finally {
            // 释放分布式锁
            if (lockAcquired) {
                cacheService.delete(lockKey);
                log.info("释放注销分布式锁: userId={}", userId);
            }
        }
    }

    /**
     * 发送重置密码验证码
     */
    @Override
    public boolean sendResetPasswordCode(String email) {
        try {
            // 1. 查询是否有这个邮箱用户
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("email", email);
            User user = userMapper.selectOne(queryWrapper);
            if (user == null) {
                throw new RuntimeException("邮箱不存在");
            }

            // 2. 检查是否已发送验证码
            String key = RESET_PASSWORD_CODE_PREFIX + email;
            String storedCode = cacheService.get(key);
            if (storedCode != null) {
                throw new RuntimeException("验证码已发送，请稍后再试");
            }

            // 3. 生成6位随机数字验证码
            String verificationCode = generateVerificationCode();

            // 4. 异步发送重置密码邮件
            sendResetPasswordEmailAsync(email, verificationCode);

            // 5. 将验证码存入Redis，设置过期时间
            cacheService.set(key, verificationCode, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);

            log.info("重置密码验证码发送成功: {}", email);
            return true;
        } catch (Exception e) {
            log.error("发送重置密码验证码失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 异步发送重置密码验证码邮件
     *
     * @param email 收件人邮箱
     * @param verificationCode 验证码
     */
    @Async("wxVirtualExecutor")
    public void sendResetPasswordEmailAsync(String email, String verificationCode) {
        String subject = "GIF-HUB 重置密码";
        String content = "您正在重置GIF-HUB账号密码，验证码是：" + verificationCode + "，" + CODE_EXPIRE_MINUTES + "分钟内有效。如非本人操作，请忽略此邮件。";
        try {
            emailStrategy.useResendEmailService(email, subject, content);
            log.info("重置密码邮件发送成功: {}", email);
        } catch (Exception e) {
            log.error("异步发送重置密码邮件失败: {}, 错误: {}", email, e.getMessage(), e);
        }
    }

    /**
     * 重置密码
     */
    @Override
    public boolean resetPassword(String email, String verificationCode, String newPassword) {
        try {
            // 1. 验证邮箱是否存在
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("email", email);
            User user = userMapper.selectOne(queryWrapper);
            if (user == null) {
                throw new RuntimeException("邮箱不存在");
            }

            // 2. 验证验证码
            String key = RESET_PASSWORD_CODE_PREFIX + email;
            String storedCode = cacheService.get(key);
            if (storedCode == null) {
                throw new RuntimeException("验证码已过期或不存在");
            }
            if (!storedCode.equals(verificationCode)) {
                throw new RuntimeException("验证码错误");
            }

            // 3. 删除验证码，防止重复使用
            cacheService.delete(key);

            // 4. 更新密码
            String decodedPassword = RsaEncryptUtil.decrypt(newPassword);
            String encodedPassword = passwordEncoder.encode(decodedPassword);
            user.setPassword(encodedPassword);
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);

            log.info("密码重置成功: email={}, userId={}", email, user.getId());
            return true;
        } catch (Exception e) {
            log.error("重置密码失败: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * 获取绑定到指定微信用户的所有邮箱账号
     *
     * @param wechatUserId 微信用户ID
     * @return 绑定的用户列表
     */
    private List<User> getBoundUsersByWechatId(Long wechatUserId) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("wechat_user_id", wechatUserId);
        return userMapper.selectList(queryWrapper);
    }

    /**
     * 更新微信用户的最后登录账号ID
     *
     * @param user 用户对象
     * @param logContext 日志上下文（用于区分调用来源）
     */
    private void updateWechatUserLastLogin(User user, String logContext) {
        if (user.getWechatUserId() == null) {
            return;
        }

        WechatUser wechatUser = wechatUserMapper.selectById(user.getWechatUserId());
        if (wechatUser != null && !user.getId().equals(wechatUser.getLastLoginUserId())) {
            wechatUser.setLastLoginUserId(user.getId());
            wechatUser.setUpdatedAt(LocalDateTime.now());
            wechatUserMapper.updateById(wechatUser);
            log.info("更新微信用户最后登录账号({}): wechatUserId={}, lastLoginUserId={}",
                    logContext, wechatUser.getId(), user.getId());
        }
    }

    /**
     * IP风险检测
     */
    private void checkIpRisk(String clientIp, String email) {
        if (clientIp != null && !IpUtil.isLocalIp(clientIp)) {
            GetIpIntelResponse ipCheck = getIpIntelService.checkIp(clientIp);
            if (ipCheck.isSuccess() && (ipCheck.getProxyScore() >= thresholdReject || ipCheck.isBadIp())) {
                log.warn("拦截高风险 IP 注册: email={}, ip={}, score={}, badIp={}",
                        email, clientIp, ipCheck.getProxyScore(), ipCheck.isBadIp());
                throw new RuntimeException("检测到异常网络环境，暂时无法注册。如有疑问请联系客服。");
            }
        }
    }
}