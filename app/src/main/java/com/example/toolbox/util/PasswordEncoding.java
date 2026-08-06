package com.example.toolbox.util;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 使用 Android Keystore 中的 AES 密钥对密码正文进行 AES-GCM 认证加密。
 *
 * <p>密钥以固定别名保存在 {@code AndroidKeyStore}，密钥材料不会由本类导出。每次加密由 Cipher 生成新的 IV，
 * 持久化格式为 {@code Base64(IV).Base64(密文与认证标签)}。GCM 在解密时同时校验完整性，密文、IV 或认证标签
 * 被篡改都会导致解密失败。</p>
 *
 * <p>该格式没有保存算法版本或密钥版本；删除应用密钥、清除系统密钥库数据或密钥失效后，既有密文将无法恢复。
 * 调用方应只持久化 {@link #encrypt(String)} 的返回值，不应把 {@link #decrypt(String)} 返回的明文写入数据库或日志。</p>
 */
public final class PasswordEncoding {
    private static final String KEY_ALIAS = "toolbox_password_key";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int TAG_LENGTH = 128;

    private final SecretKey secretKey;

    public PasswordEncoding() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            // 同一别名优先复用既有密钥，首次使用时才创建，确保历史密文仍可解密。
            SecretKey existingKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            secretKey = existingKey != null ? existingKey : createKey();
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("无法初始化密码加密密钥", e);
        }
    }

    /**
     * 加密非空密码明文。GCM 密文结果已包含 128 位认证标签，IV 则单独编码在结果前半段。
     *
     * @param plainText 仅在调用期间存在的 UTF-8 明文
     * @return 可持久化的“IV.密文”字符串
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.trim().isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    /**
     * 解析并认证解密本类生成的密文。
     *
     * @param encryptedText 数据库存储的“IV.密文”字符串，不接受裸明文
     * @return UTF-8 密码明文，仅应在业务或展示边界短暂使用
     * @throws IllegalStateException 格式无效、密钥不可用或 GCM 认证失败时抛出
     */
    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.trim().isEmpty()) {
            throw new IllegalArgumentException("加密密码不能为空");
        }
        try {
            String[] parts = encryptedText.split("\\.", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("密码密文格式不正确");
            }
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("密码解密失败", e);
        }
    }

    private static SecretKey createKey() throws GeneralSecurityException {
        // 256 位 AES 密钥仅允许执行 GCM 加解密，并明确使用 GCM 自带认证而不使用填充。
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
