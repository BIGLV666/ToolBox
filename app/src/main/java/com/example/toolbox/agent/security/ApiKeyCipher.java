package com.example.toolbox.agent.security;

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
 * 使用 Android Keystore 保护 Agent API Key 的加解密器。
 *
 * <p>AES 主密钥以固定别名生成并保留在系统 Keystore 中，应用数据库只保存 GCM 随机向量和
 * 密文的 Base64 表示，不保存可导出的主密钥。密文只能在同一 Keystore 密钥仍有效的设备环境
 * 中解密；应用数据迁移、系统安全状态变化或密钥失效都可能使旧密文不可恢复。</p>
 *
 * <p>AES-GCM 同时提供机密性和完整性校验。每次加密由 Cipher 生成新的随机向量，存储格式为
 * {@code Base64(IV).Base64(密文及认证标签)}，随机向量本身无需保密，但必须与对应密文一起保存。</p>
 */
public final class ApiKeyCipher {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "toolbox_agent_api_key";
    private final SecretKey key;

    /** 加载已有 Keystore 密钥；首次使用时创建不可导出的 AES 密钥。 */
    public ApiKeyCipher() {
        try {
            KeyStore store = KeyStore.getInstance(STORE);
            store.load(null);
            SecretKey current = (SecretKey) store.getKey(ALIAS, null);
            key = current == null ? createKey() : current;
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 API Key 加密", e);
        }
    }

    /**
     * 使用新的 GCM 随机向量加密 UTF-8 明文，并返回可存入数据库的组合字符串。
     */
    public String encrypt(String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                    + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /**
     * 解析数据库中的组合格式并完成 GCM 认证解密。
     * 格式损坏、认证标签不匹配或 Keystore 密钥不可用均统一转化为状态异常，避免返回未验证数据。
     */
    public String decrypt(String value) {
        try {
            String[] parts = value.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("API Key 解密失败", e);
        }
    }

    /**
     * 在 Android Keystore 中创建仅允许 AES-GCM 加解密的 256 位密钥。
     * 未配置用户认证约束，因此安全边界依赖应用沙箱、设备锁和 Keystore 实现，而非每次操作弹窗认证。
     */
    private static SecretKey createKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
