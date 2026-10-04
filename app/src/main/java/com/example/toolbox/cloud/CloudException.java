package com.example.toolbox.cloud;

import java.io.IOException;

/**
 * 对象存储请求失败。
 *
 * <p>服务端返回的错误体是 S3 风格的 XML，其中 {@code Code} 字段比 HTTP 状态码更能说明问题，
 * 因此这里把它单独保留下来交给界面层展示。常见取值：</p>
 * <ul>
 *   <li>{@code InvalidAccessKeyId} —— 访问密钥不存在，多半是复制时混入了空格或用了别账号的密钥。</li>
 *   <li>{@code SignatureDoesNotMatch} —— 签名不匹配，通常是区域与端点不一致，或查询参数未按名排序。</li>
 *   <li>{@code AccessDenied} —— 密钥有效但没有该操作的权限，需要检查 IAM 策略的前缀与动作。</li>
 *   <li>{@code NoSuchBucket} —— 空间名不是对象存储侧的 bucket 名。</li>
 * </ul>
 */
public final class CloudException extends IOException {
    private final int httpStatus;
    private final String code;
    private final String requestId;

    public CloudException(int httpStatus, String code, String message, String requestId) {
        super(buildMessage(httpStatus, code, message));
        this.httpStatus = httpStatus;
        this.code = code == null ? "" : code;
        this.requestId = requestId == null ? "" : requestId;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String requestId() {
        return requestId;
    }

    /** 是否为「到达了服务端、但被拒绝」的业务错误，区别于纯网络故障。 */
    public boolean isServiceRejection() {
        return httpStatus > 0;
    }

    private static String buildMessage(int httpStatus, String code, String message) {
        StringBuilder text = new StringBuilder();
        if (httpStatus > 0) {
            text.append("HTTP ").append(httpStatus);
        }
        if (code != null && !code.isEmpty()) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(code);
        }
        if (message != null && !message.isEmpty()) {
            if (text.length() > 0) {
                text.append("：");
            }
            text.append(message);
        }
        return text.length() == 0 ? "对象存储请求失败" : text.toString();
    }
}
