package com.example.toolbox.cloud;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对象存储 S3 兼容接口所需的 AWS Signature Version 4 签名。
 *
 * <p>签名过程只依赖 JDK 自带的 HMAC 与摘要实现，不引入任何对象存储 SDK，因此应用体积和构建依赖
 * 都不受影响。签名规则见 AWS SigV4 规范，这里实现的是「请求头签名」与「参数签名（预签名 URL）」
 * 两种形式，服务端两者都接受。</p>
 *
 * <p>最容易出错的一处是 canonical query：规范要求参数名先按字典序排序、再统一编码。顺序不对时
 * 签名字符串与服务端重算的结果不一致，服务端会以 403 拒绝；而不带查询参数的请求恰好不受影响，
 * 所以这类错误只在「列举对象」等带参请求上暴露。{@link #canonicalQuery(Map)} 承担排序职责，
 * 调用方不要再自行拼接查询串。</p>
 */
public final class S3Signer {
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final String EMPTY_PAYLOAD_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** 预签名 URL 使用该占位值，表示签名不覆盖请求体。 */
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

    private S3Signer() { }

    /** 一对访问凭据。通常来自七牛 IAM 子账号，权限已被策略限制在特定空间/前缀内。 */
    public static final class Credentials {
        public final String accessKey;
        public final String secretKey;

        public Credentials(String accessKey, String secretKey) {
            this.accessKey = accessKey == null ? "" : accessKey.trim();
            this.secretKey = secretKey == null ? "" : secretKey.trim();
        }

        public boolean isComplete() {
            return !accessKey.isEmpty() && !secretKey.isEmpty();
        }
    }

    /**
     * 计算请求头形式的 Authorization 值。
     *
     * @param method        大写 HTTP 方法
     * @param canonicalUri  已按段编码的路径（保留分隔符），例如 {@code /bucket/notes/2026/09/a.pdf}
     * @param canonicalQuery {@link #canonicalQuery(Map)} 的结果
     * @param headers       参与签名的头，键必须小写，且必须包含 {@code host} 与 {@code x-amz-date}
     * @param payloadSha256 请求体的 SHA-256 十六进制值；无请求体时用 {@link #emptyPayloadSha256()}
     */
    public static String authorization(String method, String canonicalUri, String canonicalQuery,
                                       Map<String, String> headers, String payloadSha256,
                                       Credentials credentials, String region) {
        String amzDate = headers.get("x-amz-date");
        if (amzDate == null || amzDate.length() < 8) {
            throw new IllegalArgumentException("签名头缺少 x-amz-date");
        }
        StringBuilder canonicalHeaders = new StringBuilder();
        List<String> names = new ArrayList<>(headers.keySet());
        for (String name : names) {
            canonicalHeaders.append(name).append(':').append(headers.get(name)).append('\n');
        }
        String signedHeaders = String.join(";", names);
        String canonicalRequest = method + "\n"
                + (canonicalUri == null || canonicalUri.isEmpty() ? "/" : canonicalUri) + "\n"
                + (canonicalQuery == null ? "" : canonicalQuery) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadSha256;

        String scope = scope(amzDate, region);
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = hex(hmac(signingKey(credentials, amzDate, region), stringToSign));
        return ALGORITHM + " Credential=" + credentials.accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /**
     * 生成预签名 GET 地址。凭据只参与本地计算，URL 本身可在有效期内被任意客户端直接下载，
     * 因此适合把「临时取件」交给外部应用或浏览器，而不用下发密钥。
     */
    public static String presignedGetUrl(String endpoint, String bucket, String key,
                                         Credentials credentials, String region, long expiresSeconds) {
        String amzDate = amzDate();
        String canonicalUri = "/" + bucket + "/" + encodePath(key);
        String scope = scope(amzDate, region);
        String query = "X-Amz-Algorithm=" + ALGORITHM
                + "&X-Amz-Credential=" + uriEncode(credentials.accessKey + "/" + scope)
                + "&X-Amz-Date=" + amzDate
                + "&X-Amz-Expires=" + expiresSeconds
                + "&X-Amz-SignedHeaders=host";
        String host = hostOf(endpoint);
        String canonicalRequest = "GET\n" + canonicalUri + "\n" + query + "\n"
                + "host:" + host + "\n\n"
                + "host\n"
                + UNSIGNED_PAYLOAD;
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = hex(hmac(signingKey(credentials, amzDate, region), stringToSign));
        return trimEndpoint(endpoint) + canonicalUri + "?" + query + "&X-Amz-Signature=" + signature;
    }

    /**
     * 把查询参数按名称排序并编码。排序是规范要求，不是风格选择：调用方传入 {@link java.util.Map}
     * 即由这里统一决定最终顺序，避免各处自行拼接产生签名不一致。
     */
    public static String canonicalQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            sorted.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(uriEncode(entry.getKey())).append('=').append(uriEncode(entry.getValue()));
        }
        return query.toString();
    }

    /** 当前 UTC 时间戳，格式为 SigV4 要求的 {@code yyyyMMdd'T'HHmmss'Z'}。 */
    public static String amzDate() {
        return ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT));
    }

    /** 空请求体的 SHA-256，供 GET/HEAD/DELETE 复用，省去重复计算。 */
    public static String emptyPayloadSha256() {
        return EMPTY_PAYLOAD_SHA256;
    }

    public static String sha256Hex(byte[] data) {
        return hex(digest("SHA-256", data));
    }

    public static String md5Hex(byte[] data) {
        return hex(digest("MD5", data));
    }

    /**
     * 按 RFC 3986 编码单个片段。{@code URLEncoder} 面向表单，会把空格编码成 {@code +}，
     * 这里统一改回 {@code %20}。
     */
    public static String uriEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 逐段编码对象键，保留分隔符，避免把目录层级编码进单个键名。 */
    public static String encodePath(String key) {
        StringBuilder path = new StringBuilder();
        for (String segment : key.split("/", -1)) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(uriEncode(segment));
        }
        return path.toString();
    }

    /** 去掉协议前缀与末尾分隔符，得到可用于 host 头的部分。 */
    public static String hostOf(String endpoint) {
        return trimEndpoint(endpoint).replaceFirst("^[a-zA-Z]+://", "").replaceAll("/.*$", "");
    }

    public static String trimEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String scope(String amzDate, String region) {
        return amzDate.substring(0, 8) + "/" + region + "/" + SERVICE + "/" + TERMINATOR;
    }

    /** 按 SigV4 约定逐层派生签名密钥：日期 → 区域 → 服务 → 终止符。 */
    private static byte[] signingKey(Credentials credentials, String amzDate, String region) {
        byte[] key = hmac(("AWS4" + credentials.secretKey).getBytes(StandardCharsets.UTF_8), amzDate.substring(0, 8));
        key = hmac(key, region);
        key = hmac(key, SERVICE);
        return hmac(key, TERMINATOR);
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("签名计算失败", error);
        }
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (Exception error) {
            throw new IllegalStateException("摘要计算失败: " + algorithm, error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(String.format("%02x", value));
        }
        return text.toString();
    }
}
