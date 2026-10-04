package com.example.toolbox.cloud;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对象存储 S3 兼容接口的极简客户端。
 *
 * <p>只实现本应用需要的动作：上传、探测、下载、删除、列举，以及"为原始文件名找一个不冲突的键"。HTTP 层
 * 沿用项目既有的 {@link HttpURLConnection} 用法，不引入第三方网络库；签名交给 {@link S3Signer}。</p>
 *
 * <h2>加密上传为什么先落临时文件</h2>
 * <p>签名要求请求头里带请求体的 SHA-256，而加密后的密文长度虽然可以预算、内容却因为随机 nonce 前缀
 * 而无法提前得知——除非把密文先算出来。三种解法里：用 {@code UNSIGNED-PAYLOAD} 依赖服务端支持（未验证），
 * 把整个文件读进内存会在几十 MB 的 PDF 上 OOM，因此选择"加密写到临时文件、再走普通上传"。内存占用恒定，
 * 代价是一次额外的临时磁盘写入，上传结束后立即删除。</p>
 *
 * <h2>校验口径</h2>
 * <p>{@link UploadResult#isVerified()} 比对的是<b>服务端 ETag 与上传内容（密文）的 MD5</b>；加密上传时
 * 另外返回明文 SHA-256（{@link UploadResult#plainSha256}），用于下载解密后再校验一次，确保解出来的
 * 内容和当初上传的一致。</p>
 */
public final class CloudClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_KEY_COLLISION_ATTEMPTS = 50;
    private static final Pattern CONTENTS_PATTERN =
            Pattern.compile("<Contents>(.*?)</Contents>", Pattern.DOTALL);
    private static final String EMPTY_SHA256 = S3Signer.emptyPayloadSha256();

    /** 传输进度回调。在发起请求的线程被调用，界面层需自行切回主线程。 */
    public interface ProgressListener {
        void onProgress(long transferred, long total);
    }

    /** 远端对象的元数据。 */
    public static final class RemoteObject {
        public final String key;
        public final long size;
        public final String etag;
        public final String lastModified;

        RemoteObject(String key, long size, String etag, String lastModified) {
            this.key = key;
            this.size = size;
            this.etag = etag;
            this.lastModified = lastModified;
        }
    }

    /** 上传结果。{@link #isVerified()} 为真是删除本地文件的前提。 */
    public static final class UploadResult {
        public final String key;
        public final long size;
        public final String md5;
        public final String etag;
        /** 明文摘要，仅加密上传时非空；下载解密后可用它复核内容。 */
        public final String plainSha256;

        UploadResult(String key, long size, String md5, String etag, String plainSha256) {
            this.key = key;
            this.size = size;
            this.md5 = md5;
            this.etag = etag;
            this.plainSha256 = plainSha256;
        }

        public boolean isVerified() {
            return etag != null && md5 != null && etag.equalsIgnoreCase(md5);
        }
    }

    private final CloudConfig config;
    private final File workDirectory;
    private volatile HttpURLConnection activeConnection;
    private volatile boolean cancelled;

    public CloudClient(CloudConfig config) {
        this(config, null);
    }

    /** {@code workDirectory} 用于加密上传时落临时密文；不加密时可以传 null。 */
    public CloudClient(CloudConfig config, File workDirectory) {
        this.config = config.copy();
        this.workDirectory = workDirectory;
    }

    public CloudConfig config() {
        return config;
    }

    /** 中断当前请求。正在阻塞的读写会因连接断开而提前失败。 */
    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    // ---------- 列举与探测 ----------

    /** 列举指定前缀下的对象。返回空列表表示前缀下暂时没有对象，而非失败。 */
    public List<RemoteObject> listObjects(String prefix, int maxKeys) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("list-type", "2");
        query.put("prefix", prefix == null ? "" : prefix);
        query.put("max-keys", String.valueOf(maxKeys));
        HttpURLConnection connection = open("GET", "", query, EMPTY_SHA256, null);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw toException(connection, status);
            }
            String body = readBody(connection);
            List<RemoteObject> objects = new ArrayList<>();
            Matcher matcher = CONTENTS_PATTERN.matcher(body);
            while (matcher.find()) {
                String chunk = matcher.group(1);
                objects.add(new RemoteObject(field(chunk, "Key"), longField(chunk, "Size"),
                        normalizeEtag(field(chunk, "ETag")), field(chunk, "LastModified")));
            }
            return objects;
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    /** 探测对象是否存在。不存在返回 {@code null}，其余错误照常抛出。 */
    public RemoteObject headObject(String key) throws IOException {
        HttpURLConnection connection = open("HEAD", key, Collections.emptyMap(), EMPTY_SHA256, null);
        try {
            int status = connection.getResponseCode();
            if (status == 404) {
                return null;
            }
            if (status < 200 || status >= 300) {
                throw toException(connection, status);
            }
            return new RemoteObject(key, connection.getContentLengthLong(),
                    normalizeEtag(connection.getHeaderField("ETag")),
                    connection.getHeaderField("Last-Modified"));
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    /**
     * 为「原始文件名」形式的对象键找一个不冲突的名字。
     *
     * <p>对象键直接用文件名是为了在控制台一眼认出文件，但同名文件直接覆盖会静默丢掉前一份。
     * 因此先探测，占用则依次尝试 {@code 名字_2.ext}、{@code 名字_3.ext}……极端情况下退回时间戳，
     * 保证永不覆盖已有对象。</p>
     */
    public String availableKey(String desiredKey) throws IOException {
        if (headObject(desiredKey) == null) {
            return desiredKey;
        }
        int dot = desiredKey.lastIndexOf('.');
        int slash = desiredKey.lastIndexOf('/');
        boolean hasExtension = dot > slash + 1;
        String base = hasExtension ? desiredKey.substring(0, dot) : desiredKey;
        String extension = hasExtension ? desiredKey.substring(dot) : "";
        for (int index = 2; index <= MAX_KEY_COLLISION_ATTEMPTS; index++) {
            String candidate = base + "_" + index + extension;
            if (headObject(candidate) == null) {
                return candidate;
            }
        }
        return base + "_" + System.currentTimeMillis() + extension;
    }

    // ---------- 上传与下载 ----------

    /**
     * 上传本地文件。
     *
     * @param encryptionKey 非空时先加密再上传；为空表示明文上传
     */
    public UploadResult putObject(String key, File source, String contentType,
                                  ProgressListener listener, byte[] encryptionKey) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("本地文件不存在");
        }
        if (encryptionKey == null) {
            return putPlain(key, source, contentType, listener);
        }
        File temporary = createWorkFile();
        try {
            String plainSha256 = encryptTo(source, temporary, encryptionKey);
            UploadResult uploaded = putPlain(key, temporary, contentType, listener);
            return new UploadResult(uploaded.key, uploaded.size, uploaded.md5, uploaded.etag, plainSha256);
        } finally {
            temporary.delete();
        }
    }

    /**
     * 下载对象到本地文件。
     *
     * <p>是否解密由<b>内容自己</b>决定：先窥探开头几个字节，是 {@link NoteCipher} 的魔数就解密，否则按
     * 原样复制。这样换设备恢复时不需要本地标记——全新安装根本没有那个标记，而内容一直是自描述的。</p>
     *
     * @param decryptionKey 解密密钥；内容加密而这里为 null 时直接报错，而不是把密文当文件交出去
     * @return true 表示下载内容是密文并已解密
     */
    public boolean getObject(String key, File target, ProgressListener listener, byte[] decryptionKey)
            throws IOException {
        HttpURLConnection connection = open("GET", key, Collections.emptyMap(), EMPTY_SHA256, null);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw toException(connection, status);
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建下载目录");
            }
            long expected = connection.getContentLengthLong();
            long transferred;
            boolean decrypted;
            try (InputStream raw = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                 CountingInputStream counting = new CountingInputStream(raw, listener, expected);
                 PushbackInputStream peekable = new PushbackInputStream(counting, NoteCipher.HEADER_LENGTH)) {
                boolean encrypted = looksEncrypted(peekable);
                if (encrypted && decryptionKey == null) {
                    throw new IOException("云端内容是加密的，需要先设置并解锁口令");
                }
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
                    if (encrypted) {
                        NoteCipher.decrypt(peekable, output, decryptionKey);
                    } else {
                        copy(peekable, output);
                    }
                    output.flush();
                }
                decrypted = encrypted;
                transferred = counting.count();
            } catch (IOException error) {
                target.delete();
                throw error;
            }
            // 无论是否解密，都应该读完整个响应体；差一截说明传输被中断。
            if (expected > 0 && transferred != expected) {
                target.delete();
                throw new IOException("下载未完成，已丢弃不完整文件");
            }
            return decrypted;
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    /** 窥探开头是否为加密魔数，并把读掉的字节放回流里，后续读取不受影响。 */
    private static boolean looksEncrypted(PushbackInputStream stream) throws IOException {
        byte[] head = new byte[4];
        int filled = 0;
        while (filled < head.length) {
            int read = stream.read(head, filled, head.length - filled);
            if (read < 0) {
                break;
            }
            filled += read;
        }
        if (filled > 0) {
            stream.unread(head, 0, filled);
        }
        return filled == head.length && NoteCipher.hasMagic(head);
    }

    /** 删除对象。对象本就不存在时服务端同样返回成功，无需特殊处理。 */
    public void deleteObject(String key) throws IOException {
        HttpURLConnection connection = open("DELETE", key, Collections.emptyMap(), EMPTY_SHA256, null);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw toException(connection, status);
            }
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    /**
     * 连通性与权限自检：列举配置前缀下的少量对象。
     *
     * <p>该动作需要「列举」权限，因此比单纯的上传更能暴露 IAM 策略配置问题。</p>
     */
    public List<RemoteObject> probe() throws IOException {
        return listObjects(config.normalizedPrefix(), 5);
    }

    // ---------- 内部实现 ----------

    /** 明文上传：先算出内容摘要，再把文件作为请求体发出。 */
    private UploadResult putPlain(String key, File source, String contentType, ProgressListener listener)
            throws IOException {
        long length = source.length();
        Digests digests = Digests.of(source);
        HttpURLConnection connection = open("PUT", key, Collections.emptyMap(), digests.sha256, contentType);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(length);
        try {
            try (OutputStream output = new BufferedOutputStream(connection.getOutputStream(), BUFFER_SIZE);
                 InputStream input = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long written = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    ensureNotCancelled();
                    output.write(buffer, 0, count);
                    written += count;
                    if (listener != null) {
                        listener.onProgress(written, length);
                    }
                }
                output.flush();
                if (written != length) {
                    throw new IOException("上传字节数与文件大小不一致，已中止");
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw toException(connection, status);
            }
            return new UploadResult(key, length, digests.md5,
                    normalizeEtag(connection.getHeaderField("ETag")), null);
        } finally {
            activeConnection = null;
            connection.disconnect();
        }
    }

    /** 加密写入临时文件，同时算出明文 SHA-256 供解密后复核。 */
    private static String encryptTo(File source, File target, byte[] encryptionKey) throws IOException {
        try {
            MessageDigest plainDigest = MessageDigest.getInstance("SHA-256");
            try (InputStream fileInput = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE);
                 DigestInputStream digesting = new DigestInputStream(fileInput, plainDigest);
                 OutputStream fileOutput = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
                NoteCipher.encrypt(digesting, fileOutput, encryptionKey);
            }
            return NoteCipher.hex(plainDigest.digest());
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("附件加密失败", error);
        }
    }

    private File createWorkFile() throws IOException {
        if (workDirectory == null) {
            throw new IOException("未配置临时目录，无法加密上传");
        }
        if (!workDirectory.exists() && !workDirectory.mkdirs()) {
            throw new IOException("无法创建临时目录");
        }
        return File.createTempFile("upload-", ".tmp", workDirectory);
    }

    // ---------- 请求构造 ----------

    /**
     * 构造带签名的连接。{@code key} 为空表示操作对象存储在空间层级（如列举）。
     * 查询参数交由 {@link S3Signer#canonicalQuery(Map)} 排序，调用方不要自行拼接。
     */
    private HttpURLConnection open(String method, String key, Map<String, String> query,
                                   String payloadSha256, String contentType) throws IOException {
        ensureNotCancelled();
        String canonicalUri = "/" + config.bucket
                + (key == null || key.isEmpty() ? "" : "/" + S3Signer.encodePath(key));
        String canonicalQuery = S3Signer.canonicalQuery(query);
        String address = config.normalizedEndpoint() + canonicalUri
                + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);

        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);

        String amzDate = S3Signer.amzDate();
        Map<String, String> signedHeaders = new LinkedHashMap<>();
        signedHeaders.put("host", S3Signer.hostOf(config.normalizedEndpoint()));
        signedHeaders.put("x-amz-content-sha256", payloadSha256);
        signedHeaders.put("x-amz-date", amzDate);
        String authorization = S3Signer.authorization(method, canonicalUri, canonicalQuery,
                signedHeaders, payloadSha256, config.credentials(), config.region);

        connection.setRequestProperty("x-amz-content-sha256", payloadSha256);
        connection.setRequestProperty("x-amz-date", amzDate);
        connection.setRequestProperty("Authorization", authorization);
        if (contentType != null && !contentType.isEmpty()) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        activeConnection = connection;
        return connection;
    }

    /** 把失败响应转成带服务端错误码的异常，便于界面直接给出可操作的提示。 */
    private CloudException toException(HttpURLConnection connection, int status) {
        String body = "";
        try {
            body = readBody(connection);
        } catch (IOException ignored) {
            // 读不到错误体时保留状态码即可，不掩盖真正的失败原因。
        }
        String code = field(body, "Code");
        if (code.isEmpty() && status == 404) {
            code = "NotFound";
        }
        return new CloudException(status, code, field(body, "Message"), field(body, "RequestId"));
    }

    private void ensureNotCancelled() throws IOException {
        if (cancelled) {
            throw new IOException("请求已取消");
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static String readBody(HttpURLConnection connection) throws IOException {
        InputStream stream = connection.getErrorStream();
        if (stream == null) {
            stream = connection.getInputStream();
        }
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
                if (buffer.size() > 256 * 1024) {
                    break;
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String normalizeEtag(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.length() > 1 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 取出 XML 中第一个同名标签的文本内容，并还原基础实体。足够解析 S3 的错误体与列举结果。 */
    private static String field(String xml, String tag) {
        if (xml == null || xml.isEmpty()) {
            return "";
        }
        Matcher matcher = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        if (!matcher.find()) {
            return "";
        }
        return unescape(matcher.group(1).trim());
    }

    private static long longField(String xml, String tag) {
        try {
            return Long.parseLong(field(xml, tag));
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    /** 一趟读取同时得到 MD5 与 SHA-256：前者用于核对服务端 ETag，后者用于签名。 */
    private static final class Digests {
        final String md5;
        final String sha256;

        private Digests(String md5, String sha256) {
            this.md5 = md5;
            this.sha256 = sha256;
        }

        static Digests of(File file) throws IOException {
            try {
                MessageDigest md5 = MessageDigest.getInstance("MD5");
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        md5.update(buffer, 0, count);
                        sha256.update(buffer, 0, count);
                    }
                }
                return new Digests(NoteCipher.hex(md5.digest()), NoteCipher.hex(sha256.digest()));
            } catch (IOException error) {
                throw error;
            } catch (Exception error) {
                throw new IOException("无法计算文件摘要", error);
            }
        }
    }

    /** 统计已读取字节数并上报进度。下载解密时统计的是密文字节，用于确认响应体读完整。 */
    private static final class CountingInputStream extends FilterInputStream {
        private final ProgressListener listener;
        private final long total;
        private long count;

        CountingInputStream(InputStream input, ProgressListener listener, long total) {
            super(input);
            this.listener = listener;
            this.total = total;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
                if (listener != null) {
                    listener.onProgress(count, total);
                }
            }
            return read;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        long count() {
            return count;
        }
    }
}
