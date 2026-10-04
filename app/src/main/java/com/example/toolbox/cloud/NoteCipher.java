package com.example.toolbox.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 笔记正文的客户端加密。
 *
 * <p>只使用 JDK 自带的密码学原语，不依赖任何 Android API，因此加密逻辑可以在普通 JVM 单元测试里
 * 完整验证——这类代码的正确性不该等到真机上才发现问题。</p>
 *
 * <h2>密钥层次</h2>
 * <pre>
 * 口令 ──PBKDF2──▶ KEK（只用来包裹主密钥）
 * 随机主密钥 MK ──AES-GCM(KEK)──▶ 存入 keyring
 * MK ──HMAC-SHA256(MK, "note:&lt;syncId&gt;")──▶ 单篇笔记的密钥
 * </pre>
 *
 * <p>主密钥与对象密钥分离带来一个实际好处：<b>改口令只需用新 KEK 重新包裹 MK，云端已上传的正文一个都
 * 不用重传</b>。反之若直接用口令派生密钥加密正文，改口令就得全量重传。</p>
 *
 * <h2>分块格式</h2>
 * <pre>
 * magic "TBXC"(4) | 版本(1) | 块大小(4) | 保留(3) | nonce 前缀(8) | 分块密文…
 * 每块 = AES-GCM(对象密钥, nonce = 前缀||块计数, 明文块, AAD = 块计数) → 密文 + 16 字节 tag
 * </pre>
 *
 * <p>正文本身只有几 KB，整体加密也能用；仍然分块是为了让加解密只有一条代码路径，避免"小数据走 A、
 * 大数据走 B"两套实现各自出错。块计数同时进入 nonce 与 AAD，<b>重排、截断、拼接都会解密失败</b>。</p>
 *
 * <p>头部（含 nonce 前缀）是明文：它不含密钥，也不泄露内容；机密性由 AES-GCM 提供。</p>
 */
public final class NoteCipher {
    /** 密文格式版本，写入头部并在解密时校验。 */
    public static final int VERSION = 1;
    /** 每块明文大小。 */
    public static final int CHUNK_SIZE = 64 * 1024;
    /** GCM 认证标签长度。 */
    public static final int TAG_LENGTH = 16;
    /** 头部长度：magic 4 + 版本 1 + 块大小 4 + 保留 3 + nonce 前缀 8。 */
    public static final int HEADER_LENGTH = 20;
    /** PBKDF2 迭代次数，在解锁耗时与暴力破解成本之间折中。 */
    public static final int PBKDF2_ITERATIONS = 210_000;

    private static final byte[] MAGIC = {'T', 'B', 'X', 'C'};
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_PREFIX_LENGTH = 8;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private NoteCipher() { }

    // ---------- 密钥派生 ----------

    /** 生成新的随机 salt。每次设置或更换口令都应换一个。 */
    public static byte[] newSalt() {
        return randomBytes(SALT_LENGTH);
    }

    /** 生成随机主密钥。主密钥只生成一次，之后一直由口令包裹保存。 */
    public static byte[] newMasterKey() {
        return randomBytes(KEY_LENGTH_BYTES);
    }

    /** 口令派生 KEK（密钥加密密钥）。KEK 只用来包裹主密钥，不直接加密正文。 */
    public static byte[] deriveKek(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BYTES * 8);
            return SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception error) {
            throw new IllegalStateException("口令派生失败", error);
        }
    }

    /**
     * 用 KEK 包裹主密钥，返回 {@code Base64(随机向量).Base64(密文)}。
     * 解开失败即口令错误，因此不需要单独保存校验值。
     */
    public static String wrapMasterKey(byte[] kek, byte[] masterKey) {
        byte[] nonce = randomBytes(12);
        byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, kek, nonce, masterKey, new byte[0]);
        return java.util.Base64.getEncoder().encodeToString(nonce) + "."
                + java.util.Base64.getEncoder().encodeToString(ciphertext);
    }

    /** 解开口令包裹的主密钥。口令错误或数据损坏时抛出。 */
    public static byte[] unwrapMasterKey(byte[] kek, String wrapped) {
        String[] parts = wrapped == null ? new String[0] : wrapped.split("\\.", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("主密钥格式不正确");
        }
        byte[] nonce = java.util.Base64.getDecoder().decode(parts[0]);
        byte[] ciphertext = java.util.Base64.getDecoder().decode(parts[1]);
        return gcm(Cipher.DECRYPT_MODE, kek, nonce, ciphertext, new byte[0]);
    }

    /**
     * 由主密钥派生单个对象的密钥。{@code scope} 区分用途，{@code id} 是稳定标识；
     * 不引入随机数，因此同一对象每次都能派生出同一把密钥，无需额外保存密钥本身。
     */
    public static byte[] objectKey(byte[] masterKey, String scope, String id) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(masterKey, MAC_ALGORITHM));
            return mac.doFinal((scope + ":" + id).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("对象密钥派生失败", error);
        }
    }

    // ---------- 长度与识别 ----------

    /** 按明文长度推算密文长度，用于在流式上传前设置 Content-Length。 */
    public static long encryptedLength(long plainLength) {
        long chunks = plainLength <= 0 ? 0 : (plainLength + CHUNK_SIZE - 1) / CHUNK_SIZE;
        return HEADER_LENGTH + plainLength + chunks * TAG_LENGTH;
    }

    /** 判断一段数据开头是否为本格式。 */
    public static boolean hasMagic(byte[] header) {
        if (header == null || header.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    public static String magicText() {
        return new String(MAGIC, StandardCharsets.US_ASCII);
    }

    // ---------- 流式加解密 ----------

    /** 边读边加密写出。内存占用为一块，与数据量无关。 */
    public static void encrypt(InputStream input, OutputStream output, byte[] objectKey) throws IOException {
        byte[] noncePrefix = randomBytes(NONCE_PREFIX_LENGTH);
        output.write(MAGIC);
        output.write(VERSION);
        writeInt(output, CHUNK_SIZE);
        output.write(new byte[3]);
        output.write(noncePrefix);

        byte[] buffer = new byte[CHUNK_SIZE];
        int counter = 0;
        while (true) {
            int filled = readUpTo(input, buffer);
            if (filled <= 0) {
                break;
            }
            byte[] plain = filled == buffer.length ? buffer : Arrays.copyOf(buffer, filled);
            output.write(gcm(Cipher.ENCRYPT_MODE, objectKey, nonce(noncePrefix, counter), plain,
                    counterBytes(counter)));
            counter++;
        }
        output.flush();
    }

    /** 边读边解密写出。任何一块认证失败都会抛出。 */
    public static void decrypt(InputStream input, OutputStream output, byte[] objectKey) throws IOException {
        byte[] header = readExactly(input, HEADER_LENGTH);
        if (header == null || !hasMagic(header)) {
            throw new IOException("不是加密数据");
        }
        if ((header[4] & 0xFF) != VERSION) {
            throw new IOException("不支持的加密格式版本");
        }
        if (readInt(header, 5) != CHUNK_SIZE) {
            throw new IOException("不支持的块大小");
        }
        byte[] noncePrefix = Arrays.copyOfRange(header, 12, 20);

        byte[] buffer = new byte[CHUNK_SIZE + TAG_LENGTH];
        int counter = 0;
        while (true) {
            int filled = readUpTo(input, buffer);
            if (filled == 0) {
                break;
            }
            if (filled <= TAG_LENGTH) {
                throw new IOException("密文被截断");
            }
            byte[] block = filled == buffer.length ? buffer : Arrays.copyOf(buffer, filled);
            output.write(gcm(Cipher.DECRYPT_MODE, objectKey, nonce(noncePrefix, counter), block,
                    counterBytes(counter)));
            counter++;
        }
        output.flush();
    }

    // ---------- 摘要 ----------

    public static String sha256Hex(byte[] data) {
        return hex(digest("SHA-256", data));
    }

    public static String md5Hex(byte[] data) {
        return hex(digest("MD5", data));
    }

    /** 把字节数组转成小写十六进制，用于把摘要存进数据库。 */
    public static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(String.format("%02x", value));
        }
        return text.toString();
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (Exception error) {
            throw new IllegalStateException("摘要计算失败: " + algorithm, error);
        }
    }

    // ---------- 内部工具 ----------

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] data, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(mode, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(data);
        } catch (Exception error) {
            // 解密方向上认证失败也走这里，统一成"密钥不对或数据被改过"。
            throw new IllegalStateException(mode == Cipher.DECRYPT_MODE
                    ? "解密失败：密钥不正确或数据已损坏" : "加密失败", error);
        }
    }

    /** nonce = 8 字节随机前缀 + 4 字节块计数，共 96 位，符合 GCM 推荐长度。 */
    private static byte[] nonce(byte[] prefix, int counter) {
        byte[] nonce = new byte[12];
        System.arraycopy(prefix, 0, nonce, 0, NONCE_PREFIX_LENGTH);
        System.arraycopy(counterBytes(counter), 0, nonce, NONCE_PREFIX_LENGTH, 4);
        return nonce;
    }

    private static byte[] counterBytes(int counter) {
        return new byte[]{
                (byte) (counter >>> 24), (byte) (counter >>> 16),
                (byte) (counter >>> 8), (byte) counter};
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static void writeInt(OutputStream output, int value) throws IOException {
        output.write(new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value});
    }

    private static int readInt(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xFF) << 24) | ((buffer[offset + 1] & 0xFF) << 16)
                | ((buffer[offset + 2] & 0xFF) << 8) | (buffer[offset + 3] & 0xFF);
    }

    /** 读满整个缓冲区或读到流末尾，返回实际字节数。 */
    private static int readUpTo(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] buffer = new byte[length];
        int total = 0;
        while (total < length) {
            int read = input.read(buffer, total, length - total);
            if (read < 0) {
                return null;
            }
            total += read;
        }
        return buffer;
    }
}
