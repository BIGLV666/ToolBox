package com.example.toolbox.cloud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * 加解密逻辑的纯 JVM 测试：不需要设备、不需要网络，跑
 * {@code gradlew :app:testDebugUnitTest} 即可。
 *
 * <p>加密代码的错误在真机上表现为"文件打不开"，排查成本高；放在这里先把往返、边界、篡改三类
 * 情况钉死。</p>
 */
public class NoteCipherTest {
    private static final byte[] KEY = NoteCipher.objectKey(new byte[32], "test", "case");

    // ---------- 往返 ----------

    @Test public void roundTripUtf8Text() throws Exception {
        byte[] plain = "一篇笔记的正文，含中文与符号 !@#".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, decrypt(encrypt(plain, KEY), KEY));
    }

    @Test public void roundTripEmpty() throws Exception {
        assertArrayEquals(new byte[0], decrypt(encrypt(new byte[0], KEY), KEY));
    }

    @Test public void roundTripExactlyOneChunk() throws Exception {
        byte[] plain = sample(NoteCipher.CHUNK_SIZE);
        assertArrayEquals(plain, decrypt(encrypt(plain, KEY), KEY));
    }

    @Test public void roundTripOneByteOverChunk() throws Exception {
        byte[] plain = sample(NoteCipher.CHUNK_SIZE + 1);
        assertArrayEquals(plain, decrypt(encrypt(plain, KEY), KEY));
    }

    @Test public void roundTripManyChunksWithTail() throws Exception {
        byte[] plain = sample(NoteCipher.CHUNK_SIZE * 3 + 123);
        assertArrayEquals(plain, decrypt(encrypt(plain, KEY), KEY));
    }

    // ---------- 长度预算 ----------

    @Test public void encryptedLengthIsPredictable() throws Exception {
        long[] sizes = {0, 1, 100, NoteCipher.CHUNK_SIZE - 1, NoteCipher.CHUNK_SIZE,
                NoteCipher.CHUNK_SIZE + 1, NoteCipher.CHUNK_SIZE * 2, 250_000};
        for (long size : sizes) {
            byte[] plain = sample((int) size);
            assertEquals("明文 " + size + " 字节的密文长度应可预算",
                    NoteCipher.encryptedLength(size), encrypt(plain, KEY).length);
        }
    }

    // ---------- 失败路径 ----------

    @Test public void wrongKeyFails() throws Exception {
        byte[] cipher = encrypt(sample(5000), KEY);
        byte[] otherKey = NoteCipher.objectKey(new byte[32], "test", "another");
        expectFailure(cipher, otherKey);
    }

    @Test public void tamperedCiphertextFails() throws Exception {
        byte[] cipher = encrypt(sample(5000), KEY);
        cipher[cipher.length - 1] ^= 0x01;
        expectFailure(cipher, KEY);
    }

    @Test public void truncatedCiphertextFails() throws Exception {
        byte[] cipher = encrypt(sample(NoteCipher.CHUNK_SIZE * 2), KEY);
        byte[] truncated = new byte[cipher.length - 100];
        System.arraycopy(cipher, 0, truncated, 0, truncated.length);
        // 截断后最后一块不完整，认证必然失败；即使恰好落在块边界上，块计数也对不上。
        expectFailure(truncated, KEY);
    }

    @Test public void reorderedChunksFail() throws Exception {
        byte[] plain = sample(NoteCipher.CHUNK_SIZE * 2);
        byte[] cipher = encrypt(plain, KEY);
        byte[] swapped = cipher.clone();
        int blockSize = NoteCipher.CHUNK_SIZE + NoteCipher.TAG_LENGTH;
        // 交换第一块与第二块密文：nonce 与 AAD 都含块计数，位置换了就必须解不开。
        System.arraycopy(cipher, NoteCipher.HEADER_LENGTH + blockSize, swapped, NoteCipher.HEADER_LENGTH, blockSize);
        System.arraycopy(cipher, NoteCipher.HEADER_LENGTH, swapped, NoteCipher.HEADER_LENGTH + blockSize, blockSize);
        expectFailure(swapped, KEY);
    }

    @Test public void plainTextIsNotAcceptedAsCipher() throws Exception {
        byte[] plain = "%PDF-1.7\n这不是密文".getBytes(StandardCharsets.UTF_8);
        expectFailure(plain, KEY);
    }

    @Test public void magicDetection() {
        assertFalse(NoteCipher.hasMagic("%PDF".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(NoteCipher.hasMagic(null));
        assertTrue(NoteCipher.hasMagic(encryptQuietly(new byte[10])));
    }

    // ---------- keyring ----------

    @Test public void keyringUnlockReturnsSameMasterKey() throws Exception {
        Keyring created = Keyring.create("correct horse".toCharArray());
        String text = created.toText();
        assertTrue(Keyring.looksLike(text));

        Keyring unlocked = Keyring.unlock("correct horse".toCharArray(), text);
        assertArrayEquals(created.masterKey(), unlocked.masterKey());
        assertArrayEquals(created.noteKey("sync-1"), unlocked.noteKey("sync-1"));
    }

    @Test public void keyringRejectsWrongPassword() throws Exception {
        String text = Keyring.create("right".toCharArray()).toText();
        try {
            Keyring.unlock("wrong".toCharArray(), text);
            fail("错误口令竟然解锁成功");
        } catch (Keyring.WrongPasswordException expected) {
            // 期望路径
        }
    }

    @Test public void keyringRejectsCorruptedText() {
        try {
            Keyring.unlock("whatever".toCharArray(), "tbx-keyring-v1\niterations=1\n");
            fail("缺少 salt 与 wrapped 竟然解锁成功");
        } catch (Keyring.WrongPasswordException expected) {
            // 期望路径
        }
    }

    @Test public void rewrapKeepsMasterKeyAndRotatesPassword() throws Exception {
        Keyring original = Keyring.create("old-pass".toCharArray());
        Keyring rewrapped = original.rewrap("new-pass".toCharArray());

        // 改口令不应改变主密钥，否则云端已上传的正文全部作废。
        Keyring unlocked = Keyring.unlock("new-pass".toCharArray(), rewrapped.toText());
        assertArrayEquals(original.masterKey(), unlocked.masterKey());
        try {
            Keyring.unlock("old-pass".toCharArray(), rewrapped.toText());
            fail("旧口令在新 keyring 上竟然仍然有效");
        } catch (Keyring.WrongPasswordException expected) {
            // 期望路径
        }
    }

    @Test public void noteKeysDifferPerNoteAndScope() {
        byte[] master = new byte[32];
        assertFalse(java.util.Arrays.equals(
                NoteCipher.objectKey(master, "note", "a"),
                NoteCipher.objectKey(master, "note", "b")));
        // 同一标识下不同用途必须派生不同密钥。
        assertFalse(java.util.Arrays.equals(
                NoteCipher.objectKey(master, "note", "a"),
                NoteCipher.objectKey(master, "file", "a")));
    }

    // ---------- 工具 ----------

    private static byte[] encrypt(byte[] plain, byte[] key) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NoteCipher.encrypt(new ByteArrayInputStream(plain), output, key);
        return output.toByteArray();
    }

    private static byte[] decrypt(byte[] cipher, byte[] key) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NoteCipher.decrypt(new ByteArrayInputStream(cipher), output, key);
        return output.toByteArray();
    }

    private static byte[] encryptQuietly(byte[] plain) {
        try {
            return encrypt(plain, KEY);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** 损坏、错密钥、截断都由认证失败或格式校验拦下，这里只断言"必须失败"。 */
    private static void expectFailure(byte[] cipher, byte[] key) {
        try {
            decrypt(cipher, key);
            fail("损坏或不匹配的密文竟然解密成功");
        } catch (Exception expected) {
            // 期望路径
        }
    }

    private static byte[] sample(int length) {
        byte[] data = new byte[length];
        new Random(length).nextBytes(data);
        return data;
    }
}
