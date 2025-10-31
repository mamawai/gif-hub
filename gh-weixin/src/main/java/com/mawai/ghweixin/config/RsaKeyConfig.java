package com.mawai.ghweixin.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import java.security.*;
import java.util.Base64;

/**
 * RSA 密钥配置类
 * 负责在应用启动时生成和管理 RSA 密钥对
 */
@Configuration
@Slf4j
public class RsaKeyConfig {

    /**
     * -- GETTER --
     *  获取公钥
     */
    // 公钥（可以公开）
    @Getter
    private static PublicKey publicKey;

    /**
     * -- GETTER --
     *  获取私钥
     */
    // 私钥（绝对保密）
    @Getter
    private static PrivateKey privateKey;

    /**
     * 应用启动时自动执行，生成 RSA 密钥对
     */
    @PostConstruct
    public void generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);  // 2048 位密钥

            KeyPair keyPair = generator.generateKeyPair();

            publicKey = keyPair.getPublic();   // 公钥
            privateKey = keyPair.getPrivate(); // 私钥

            log.info("✅ RSA 密钥对生成成功");
        } catch (NoSuchAlgorithmException e) {
            log.error("❌ RSA 密钥对生成失败: {}", e.getMessage());
            throw new RuntimeException("RSA 密钥对生成失败", e);
        }
    }

    /**
     * 获取公钥的 Base64 字符串（用于传给前端）
     */
    public static String getPublicKeyString() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }
}
