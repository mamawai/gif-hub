package com.mawai.ghweixin.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mawai.ghcommon.service.CacheService;
import com.mawai.ghmbplus.dao.UserCategoryMapper;
import com.mawai.ghmbplus.dao.UserMapper;
import com.mawai.ghmbplus.model.User;
import com.mawai.ghmbplus.model.UserCategory;
import com.mawai.ghweixin.dto.UserInfoDTO;
import com.mawai.ghweixin.service.EmailAuthService;
import com.mawai.ghweixin.strategy.EmailStrategy;
import com.mawai.ghweixin.utils.PasswordEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱认证服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailAuthServiceImpl implements EmailAuthService {

    private final CacheService cacheService;
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
     * 验证码有效期（分钟）
     */
    private static final long CODE_EXPIRE_MINUTES = 60;
    
    /**
     * 分布式锁超时时间（秒）
     */
    private static final long LOCK_TIMEOUT_SECONDS = 10;

    @Override
    public boolean sendVerificationCode(String email) {
        try {
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
            
            // 异步发送邮件
            sendEmailAsync(email, verificationCode);
            
            // 将验证码存入Redis，设置过期时间
            cacheService.set(key, verificationCode, CODE_EXPIRE_MINUTES, TimeUnit.MINUTES);
            
            return true;
        } catch (Exception e) {
            log.error("发送验证码邮件失败: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 异步发送邮件
     */
    @Async("emailSendExecutor")
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
     * 是否验证失败
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
            
            // 更新用户信息
            user.setEmail(email);
            user.setEmailVerified((byte) 1); // 注册
            user.setPassword(passwordEncoder.encode(password)); // 密码加密存储
            user.setNickname(nickname);
            user.setStatus((byte) 1);  // 正常状态
            user.setUpdatedAt(LocalDateTime.now());
            userMapper.updateById(user);

            // 创建默认分类
            userCategoryMapper.insert(new UserCategory().setUserId(userId).setCategoryName("默认"));
            
            // 设置邮箱认证状态
            StpUtil.getSession().set("emailAuth", "full");

        } finally {
            // 操作完成后释放锁
            if (lockAcquired) {
                cacheService.delete(lockKey);
                log.info("释放邮箱注册分布式锁: {}", email);
            }
        }
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
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("密码错误");
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
    public UserInfoDTO checkAndGet() {
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

        return UserInfoDTO.builder()
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
} 