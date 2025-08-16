package com.mawai.ghweixin.utils;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码加密工具类
 */
@Component
public class PasswordEncoder {

    private static final int SALT_LENGTH = 16;
    private static final String ALGORITHM = "SHA-256";
    private static final int ITERATIONS = 10000;

    /**
     * 加密密码
     * @param rawPassword 原始密码
     * @return 加密后的密码
     */
    public String encode(String rawPassword) {
        try {
            // 生成随机盐
            byte[] salt = generateSalt();
            
            // 使用盐对密码进行哈希
            byte[] hash = hash(rawPassword.toCharArray(), salt, ITERATIONS, ALGORITHM);
            
            // 将盐和哈希值拼接，并进行Base64编码
            byte[] combined = new byte[salt.length + hash.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(hash, 0, combined, salt.length, hash.length);
            
            return Base64.getEncoder().encodeToString(combined);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("加密算法不可用", e);
        }
    }

    /**
     * 验证密码
     * @param rawPassword 原始密码
     * @param encodedPassword 加密后的密码
     * @return 是否匹配
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        try {
            // 解码存储的密码
            byte[] combined = Base64.getDecoder().decode(encodedPassword);
            
            // 提取盐
            byte[] salt = new byte[SALT_LENGTH];
            System.arraycopy(combined, 0, salt, 0, salt.length);
            
            // 使用相同的盐和算法对输入的密码进行哈希
            byte[] hash = hash(rawPassword.toCharArray(), salt, ITERATIONS, ALGORITHM);
            
            // 比较哈希值
            int hashLength = combined.length - salt.length;
            for (int i = 0; i < hashLength; i++) {
                if (hash[i] != combined[salt.length + i]) {
                    return false;
                }
            }
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成随机盐
     * @return 随机盐字节数组
     */
    private byte[] generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return salt;
    }

    /**
     * 对密码进行哈希
     * @param password 密码字符数组
     * @param salt 盐
     * @param iterations 迭代次数
     * @param algorithm 算法
     * @return 哈希后的字节数组
     * @throws NoSuchAlgorithmException 如果算法不可用
     */
    private byte[] hash(char[] password, byte[] salt, int iterations, String algorithm) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        
        // 将密码转换为字节数组
        byte[] passwordBytes = new byte[password.length * 2];
        for (int i = 0; i < password.length; i++) {
            passwordBytes[i * 2] = (byte) (password[i] >> 8);
            passwordBytes[i * 2 + 1] = (byte) password[i];
        }
        
        // 添加盐
        digest.update(salt);
        byte[] result = digest.digest(passwordBytes);
        
        // 多次迭代
        for (int i = 1; i < iterations; i++) {
            digest.reset();
            result = digest.digest(result);
        }
        
        return result;
    }
} 