package com.mawai.ghweixin.utils;

import com.mawai.ghweixin.config.RsaKeyConfig;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * RSA 加密解密工具类
 */
public class RsaEncryptUtil {

    /**
     * 使用公钥加密（一般不用，前端用公钥加密）
     */
    public static String encrypt(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, RsaKeyConfig.getPublicKey());

        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * 使用私钥解密（后端解密前端发来的密文）
     */
    public static String decrypt(String encryptedText) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, RsaKeyConfig.getPrivateKey());

        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}
