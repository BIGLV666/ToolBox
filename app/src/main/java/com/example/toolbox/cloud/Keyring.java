package com.example.toolbox.cloud;

import java.util.Base64;

/**
 * 口令包裹的主密钥（keyring）。
 *
 * <p>云端只保存密文，因此必须有一个「用口令换回主密钥」的凭据载体。这个类就是它：保存 KDF 参数、
 * salt 与被 KEK 包裹的主密钥，本身不含明文密钥，可以直接放在云端而不加密。</p>
 *
 * <p>格式用简单的 {@code key=value} 行文本而不是 JSON，原因是整个加密路径不依赖 Android 的
 * {@code org.json}，从而能在普通 JVM 单元测试里完整验证（Android 的 org.json 在单元测试中是空实现）。</p>
 *
 * <pre>
 * tbx-keyring-v1
 * kdf=PBKDF2WithHmacSHA256
 * iterations=210000
 * salt=&lt;base64&gt;
 * wrapped=&lt;base64 iv&gt;.&lt;base64 密文&gt;
 * </pre>
 *
 * <p>解开失败即口令错误——GCM 的认证标签天然充当了校验值，不需要额外存一个"口令是否正确"的标记。</p>
 */
public final class Keyring {
    public static final String HEADER = "tbx-keyring-v1";

    private final byte[] salt;
    private final int iterations;
    private final String wrapped;
    private final byte[] masterKey;

    private Keyring(byte[] salt, int iterations, String wrapped, byte[] masterKey) {
        this.salt = salt;
        this.iterations = iterations;
        this.wrapped = wrapped;
        this.masterKey = masterKey;
    }

    /** 首次设置口令：生成新 salt 与新主密钥。 */
    public static Keyring create(char[] password) {
        byte[] salt = NoteCipher.newSalt();
        byte[] masterKey = NoteCipher.newMasterKey();
        byte[] kek = NoteCipher.deriveKek(password, salt, NoteCipher.PBKDF2_ITERATIONS);
        return new Keyring(salt, NoteCipher.PBKDF2_ITERATIONS,
                NoteCipher.wrapMasterKey(kek, masterKey), masterKey);
    }

    /** 用口令解出主密钥。口令错或数据损坏都抛 {@link WrongPasswordException}。 */
    public static Keyring unlock(char[] password, String text) throws WrongPasswordException {
        Keyring parsed = parse(text);
        try {
            byte[] kek = NoteCipher.deriveKek(password, parsed.salt, parsed.iterations);
            byte[] masterKey = NoteCipher.unwrapMasterKey(kek, parsed.wrapped);
            return new Keyring(parsed.salt, parsed.iterations, parsed.wrapped, masterKey);
        } catch (RuntimeException error) {
            throw new WrongPasswordException();
        }
    }

    /**
     * 用本机缓存的主密钥直接构造，跳过口令解包。
     *
     * <p>主密钥是在设置口令时缓存进 Keystore 的，本机日常使用走这条路可以完全不用重复输入口令；文本仍会
     * 被解析，格式损坏时同样拒绝。</p>
     */
    public static Keyring withMasterKey(String text, byte[] masterKey) throws WrongPasswordException {
        if (masterKey == null || masterKey.length == 0) {
            throw new WrongPasswordException();
        }
        Keyring parsed = parse(text);
        return new Keyring(parsed.salt, parsed.iterations, parsed.wrapped, masterKey);
    }

    /**
     * 更换口令：主密钥保持不变，只换一层包裹。
     * 因此云端已上传的正文不需要重新加密或重传，只要把新的 keyring 覆盖上去即可。
     */
    public Keyring rewrap(char[] newPassword) {
        byte[] newSalt = NoteCipher.newSalt();
        byte[] kek = NoteCipher.deriveKek(newPassword, newSalt, NoteCipher.PBKDF2_ITERATIONS);
        return new Keyring(newSalt, NoteCipher.PBKDF2_ITERATIONS,
                NoteCipher.wrapMasterKey(kek, masterKey), masterKey);
    }

    /** 序列化成可上传/可缓存的文本。 */
    public String toText() {
        return HEADER + "\n"
                + "kdf=PBKDF2WithHmacSHA256\n"
                + "iterations=" + iterations + "\n"
                + "salt=" + Base64.getEncoder().encodeToString(salt) + "\n"
                + "wrapped=" + wrapped + "\n";
    }

    /** 主密钥副本。调用方不应长期持有，更不要落盘。 */
    public byte[] masterKey() {
        return masterKey.clone();
    }

    /** 某篇笔记的正文密钥。参数是笔记的 syncId，跨设备稳定。 */
    public byte[] noteKey(String syncId) {
        return NoteCipher.objectKey(masterKey, "note", syncId);
    }

    /** 判断一段文本是否为本格式，用于识别云端是否已经存在 keyring。 */
    public static boolean looksLike(String text) {
        return text != null && text.startsWith(HEADER);
    }

    private static Keyring parse(String text) throws WrongPasswordException {
        if (!looksLike(text)) {
            throw new WrongPasswordException();
        }
        byte[] salt = null;
        String wrapped = null;
        int iterations = NoteCipher.PBKDF2_ITERATIONS;
        for (String line : text.split("\n")) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if ("salt".equals(key)) {
                try {
                    salt = Base64.getDecoder().decode(value);
                } catch (IllegalArgumentException error) {
                    throw new WrongPasswordException();
                }
            } else if ("wrapped".equals(key)) {
                wrapped = value;
            } else if ("iterations".equals(key)) {
                try {
                    iterations = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    // 保留默认值，避免因一行脏数据整体不可用。
                }
            }
        }
        if (salt == null || salt.length == 0 || wrapped == null || wrapped.isEmpty()) {
            throw new WrongPasswordException();
        }
        return new Keyring(salt, iterations, wrapped, null);
    }

    /** 口令不正确，或 keyring 内容损坏。两种情况对用户的处理方式相同：重新输入口令。 */
    public static final class WrongPasswordException extends Exception {
        public WrongPasswordException() {
            super("口令不正确，或加密凭据已损坏");
        }
    }
}
