package com.example.toolbox.cloud;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 七牛云原生管理接口（与 S3 兼容接口是两套东西）。
 *
 * <p>本应用只用它做一件事：给<b>已上传</b>的对象设置「N 天后过期删除」。删除笔记时用这个接口而不是
 * 立即删除对象，能留下一段缓冲期——误删还来得及挽回，同时空间最终会被自动释放。</p>
 *
 * <p>认证方式也不同：不是 S3 的 SigV4，而是七牛的「管理凭证」——
 * {@code signingStr = method + " " + path + "\nHost: " + host + "\n\n"}（无请求体、无 Content-Type 时
 * 只有这三段），HMAC-SHA1 后用 url-safe Base64 编码，再拼成 {@code AK:sign}，放进
 * {@code Authorization: Qiniu <token>}。</p>
 */
public final class QiniuManagementClient {
    /** 管理接口域名。文档中的示例同时出现过 rs.qiniu.com，这里按生命周期文档使用 qiniuapi 域名。 */
    private static final String HOST = "rs.qiniuapi.com";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** 服务端约定：待操作资源不存在。对"设置过期"而言等价于目标已经消失，可直接视为完成。 */
    public static final String CODE_RESOURCE_MISSING = "612";
    private static final Pattern ERROR_PATTERN = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*(\\d+)");

    /** stat 返回中与本应用相关的字段。 */
    public static final class ObjectStat {
        public final long size;
        /** 上传时间，单位 100 纳秒（七牛约定），转成毫秒需除以 10000。 */
        public final long putTime;
        /** 过期删除的绝对时间（Unix 秒），未设置时为 0。 */
        public final long expirationSeconds;

        ObjectStat(long size, long putTime, long expirationSeconds) {
            this.size = size;
            this.putTime = putTime;
            this.expirationSeconds = expirationSeconds;
        }

        public boolean hasExpiration() {
            return expirationSeconds > 0;
        }
    }

    private final CloudConfig config;

    public QiniuManagementClient(CloudConfig config) {
        this.config = config.copy();
    }

    /**
     * 设置对象在 {@code days} 天后过期删除；{@code days = 0} 表示取消过期设置。
     *
     * @return true 表示设置成功；false 表示对象已不存在（视为完成，调用方无需重试）
     */
    public boolean setDeleteAfterDays(String key, int days) throws IOException {
        String path = "/deleteAfterDays/" + encodedEntry(key) + "/" + days;
        HttpResponse response = request("POST", path);
        if (response.status == 200) {
            return true;
        }
        if (CODE_RESOURCE_MISSING.equals(response.code)) {
            return false;
        }
        throw new CloudException(response.status, response.code, response.message, "");
    }

    /** 查询对象元信息，用于核对过期时间是否按预期生效。 */
    public ObjectStat stat(String key) throws IOException {
        HttpResponse response = request("GET", "/stat/" + encodedEntry(key));
        if (response.status != 200) {
            throw new CloudException(response.status, response.code, response.message, "");
        }
        return new ObjectStat(number(response.body, "fsize"), number(response.body, "putTime"),
                number(response.body, "expiration"));
    }

    // ---------- 请求与签名 ----------

    private HttpResponse request(String method, String path) throws IOException {
        String address = "https://" + HOST + path;
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        // 不设置 Content-Type 也不带请求体：这样待签名字符串就是文档示例里的最短形式，减少签错的可能。
        connection.setRequestProperty("Authorization", "Qiniu " + managementToken(method, path));

        HttpResponse result = new HttpResponse();
        result.status = connection.getResponseCode();
        result.body = readBody(connection);
        result.code = extract(result.body, ERROR_PATTERN);
        result.message = result.code.isEmpty() ? result.body.trim() : result.code;
        connection.disconnect();
        return result;
    }

    /**
     * 生成管理凭证。无请求体、无额外头时：
     * {@code signingStr = "POST /deleteAfterDays/<entry>/7\nHost: rs.qiniuapi.com\n\n"}
     */
    private String managementToken(String method, String path) {
        String signingText = method + " " + path + "\nHost: " + HOST + "\n\n";
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(config.secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] signature = mac.doFinal(signingText.getBytes(StandardCharsets.UTF_8));
            return config.accessKey + ":"
                    + Base64.getUrlEncoder().encodeToString(signature);
        } catch (Exception error) {
            throw new IllegalStateException("管理凭证生成失败", error);
        }
    }

    /** EncodedEntryURI = url-safe Base64("空间名:对象键")，带填充。 */
    private String encodedEntry(String key) {
        String entry = config.bucket + ":" + key;
        return Base64.getUrlEncoder().encodeToString(entry.getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(HttpURLConnection connection) {
        try {
            InputStream stream = connection.getErrorStream();
            if (stream == null) {
                stream = connection.getInputStream();
            }
            if (stream == null) {
                return "";
            }
            try (InputStream input = stream) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    buffer.write(chunk, 0, count);
                    if (buffer.size() > 64 * 1024) {
                        break;
                    }
                }
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException error) {
            return "";
        }
    }

    private static String extract(String body, Pattern pattern) {
        Matcher matcher = pattern.matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static long number(String body, String field) {
        Matcher matcher = Pattern.compile(String.format(NUMBER_PATTERN.pattern(), field))
                .matcher(body == null ? "" : body);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static final class HttpResponse {
        int status;
        String body = "";
        String code = "";
        String message = "";
    }
}
