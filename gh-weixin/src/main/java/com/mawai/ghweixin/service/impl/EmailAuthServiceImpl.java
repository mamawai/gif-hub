package com.mawai.ghweixin.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghcommon.service.UserNicknameCacheService;
import com.mawai.ghcommon.utils.SpringUtils;
import com.mawai.ghmbplus.dao.UserCategoryMapper;
import com.mawai.ghmbplus.dao.UserMapper;
import com.mawai.ghmbplus.model.User;
import com.mawai.ghmbplus.model.UserCategory;
import com.mawai.ghweixin.vo.UserInfoVO;
import com.mawai.ghweixin.event.AccountDeleteEvent;
import com.mawai.ghweixin.service.EmailAuthService;
import com.mawai.ghweixin.strategy.EmailStrategy;
import com.mawai.ghweixin.utils.PasswordEncoder;
import com.mawai.ghweixin.utils.RsaEncryptUtil;
import com.mawai.ghweixin.vo.LoginResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
    private final PasswordEncoder passwordEncoder;
    private final EmailStrategy emailStrategy;
    private final UserCategoryMapper userCategoryMapper;

    /**
     * 验证码在Redis中的前缀
     */
    private static final String CODE_KEY_PREFIX = "EMAIL_VERIFICATION_CODE:";
    
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
            // 检查邮箱是否在黑名单中（24小时内注销的邮箱）
            String blacklistKey = DELETED_EMAIL_BLACKLIST_PREFIX + email;
            if (cacheService.get(blacklistKey) != null) {
                throw new RuntimeException("该邮箱已注销，24小时内无法重新注册");
            }

            // 校验邮箱是否正确 -- 当用户注册邮箱但是未登录时会走这个校验
            String tryToGetEmail = userMapper.selectById(StpUtil.getLoginIdAsLong()).getEmail();
            if (tryToGetEmail != null && !email.equals(tryToGetEmail)) {
                throw new RuntimeException("邮箱错误，请输入正确的绑定邮箱");
            }

            // 校验redis中是否存在验证码
            String key = CODE_KEY_PREFIX + email;
            String storedCode = cacheService.get(key);
            if (storedCode != null) {
                throw new RuntimeException("验证码已发送，请稍后再试");
            }

            // 生成6位随机数字验证码
            String verificationCode = generateVerificationCode();
            
            // TODO 创建EmailMessage对象
            // 异步发送邮件
            sendEmailAsync(email, verificationCode);
            
            // 将验证码存入Redis，设置过期时间
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
    @Transactional(rollbackFor = Exception.class)
    public void register(String email, String password, String verificationCode,String nickname) {

        // 如果未登录会抛出异常
        Long userId = StpUtil.getLoginIdAsLong();

        // 验证验证码
        if (isVerifyFail(email, verificationCode)) {
            throw new RuntimeException("验证码错误或已过期");
        }

        // 创建分布式锁的key
        String lockKey = REGISTER_LOCK_PREFIX + email;
        // 尝试获取分布式锁，值为当前时间戳，过期时间为10秒
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

            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("email", email);
            if (userMapper.selectOne(queryWrapper) != null) {
                throw new RuntimeException("该邮箱已注册");
            }

            // 查询用户 -- 用户注册已经在微信登陆中实现
            User user = userMapper.selectById(userId);
            if (user == null) {
                throw new RuntimeException("用户不存在"); //正常来说不会出现这种情况    
            }

            // 如果nickname是空就随机一个uuid
            if (!StringUtils.hasText(nickname)) {
                nickname = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            }
            
            // 更新用户信息
            user.setEmail(email);
            user.setEmailVerified((byte) 1); // 注册
            user.setPassword(passwordEncoder.encode(RsaEncryptUtil.decrypt(password))); // 先私钥解密，再密码加密存储
            user.setNickname(nickname);
            user.setStatus((byte) 1);  // 正常状态
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);

            // 写入昵称缓存
            userNicknameCacheService.updateNickname(userId, nickname);

            // 创建默认分类
            userCategoryMapper.insert(new UserCategory().setUserId(userId).setCategoryName("默认"));
            
            // 设置邮箱认证状态
            StpUtil.getSession().set("emailAuth", "full");

        } catch (Exception e) {
            log.error("用户注册失败: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            // 操作完成后释放锁
            if (lockAcquired) {
                cacheService.delete(lockKey);
                log.info("释放邮箱注册分布式锁: {}", email);
            }
        }
    }

    /**
     * Web端发送验证码
     */
    @Override
    public boolean sendWebVerificationCode(String email) {
        try {
            // 1. 检查邮箱是否在黑名单中（24小时内注销的邮箱）
            String blacklistKey = DELETED_EMAIL_BLACKLIST_PREFIX + email;
            if (cacheService.get(blacklistKey) != null) {
                throw new RuntimeException("该邮箱已注销，24小时内无法重新注册");
            }

            // 2. 查询是否有这个邮箱用户
            QueryWrapper<User> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("email", email);
            User user = userMapper.selectOne(queryWrapper);
            if (user == null) {
                throw new RuntimeException("邮箱不存在，请前往小程序注册");
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
            throw new RuntimeException("用户不存在");
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
        
        return LoginResultVO.builder()
                .token(StpUtil.getTokenValue())
                .build();
    }

    /**
     * 邮箱密码登录 -- 注册后登录
     */
    @Override
    public Boolean loginByPassword(String email, String password) {
        
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);

        if (user == null) {
            throw new RuntimeException("用户不存在"); // 正常来说不会出现这种情况
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

        // 返回登录结果
        return true;
    }

    @Override
    public Boolean loginByCode(String email, String verificationCode) {

        Long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);

        if (user == null) {
            // 先不验证验证码
            throw new RuntimeException("用户不存在"); // 正常来说不会出现这种情况
        }

        if (user.getEmailVerified() == 0) {
            throw new RuntimeException("请先注册邮箱");
        }

        // 验证邮箱
        if (!user.getEmail().equals(email)) {
            throw new RuntimeException("邮箱错误");
        }

        // 验证验证码
        if (isVerifyFail(email, verificationCode)) {
            throw new RuntimeException("验证码错误或已过期");
        }
        
        // 设置邮箱认证状态
        StpUtil.getSession().set("emailAuth", "full");
        
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
}