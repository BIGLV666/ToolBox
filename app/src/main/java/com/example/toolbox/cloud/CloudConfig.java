package com.example.toolbox.cloud;

/**
 * 对象存储连接参数。
 *
 * <p>默认值指向七牛云华北-河北（cn-north-1）的 S3 兼容端点，与 {@code biglv-test1} 空间所在区域一致；
 * 区域与端点必须匹配，否则请求签名能算出来但服务端会以 {@code SignatureDoesNotMatch} 拒绝。</p>
 *
 * <p>凭据建议使用七牛 IAM 子账号，策略限制到单个空间的前缀（如 {@code qrn:kodo:::biglv-test1/notes/*}）。
 * 这样即使密钥随安装包泄露，受影响范围也只限于该前缀，而不会波及整个账号。</p>
 */
public final class CloudConfig {
    /** S3 兼容服务要求的签名区域标识。 */
    public static final String DEFAULT_REGION = "cn-north-1";
    public static final String DEFAULT_ENDPOINT = "https://s3.cn-north-1.qiniucs.com";
    public static final String DEFAULT_PREFIX = "notes/";

    /**
     * 区域与 S3 兼容端点一一对应。
     *
     * <p>签名时区域参与密钥派生，端点决定请求地址，两者必须指向同一区域，否则服务端返回
     * {@code SignatureDoesNotMatch}。界面上因此只让用户选区域、由区域推导端点，从控件层面
     * 消除这一对参数被填错的可能。</p>
     */
    public static final class Region {
        public final String label;
        public final String id;
        public final String endpoint;

        Region(String label, String id, String endpoint) {
            this.label = label;
            this.id = id;
            this.endpoint = endpoint;
        }

        @Override
        public String toString() {
            return label + " (" + id + ")";
        }
    }

    public static final Region[] REGIONS = {
            new Region("华北-河北", "cn-north-1", "https://s3.cn-north-1.qiniucs.com"),
            new Region("华东-浙江", "cn-east-1", "https://s3.cn-east-1.qiniucs.com"),
            new Region("华东-浙江2", "cn-east-2", "https://s3.cn-east-2.qiniucs.com"),
            new Region("华南-广东", "cn-south-1", "https://s3.cn-south-1.qiniucs.com"),
            new Region("亚太-新加坡", "ap-southeast-1", "https://s3.ap-southeast-1.qiniucs.com"),
            new Region("亚太-河内", "ap-southeast-2", "https://s3.ap-southeast-2.qiniucs.com"),
            new Region("亚太-胡志明", "ap-southeast-3", "https://s3.ap-southeast-3.qiniucs.com"),
            new Region("北美-洛杉矶", "us-north-1", "https://s3.us-north-1.qiniucs.com"),
    };

    /** 按区域标识查表；未知区域返回华北-河北，保证界面不会因配置脏数据而崩溃。 */
    public static Region regionOf(String id) {
        for (Region region : REGIONS) {
            if (region.id.equals(id)) {
                return region;
            }
        }
        return REGIONS[0];
    }

    /** 是否启用云端存储。关闭时附件沿用本地存储，行为与改造前一致。 */
    public boolean enabled;
    public String bucket = "";
    public String region = DEFAULT_REGION;
    public String endpoint = DEFAULT_ENDPOINT;
    /** 上传成功后如何处理手机上的原始文件。 */
    public static final int DELETE_KEEP = 0;
    /** 总是删除原文件，本地一份不留。默认值。 */
    public static final int DELETE_ALWAYS = 1;
    /** 每个文件上传完成后询问一次。 */
    public static final int DELETE_ASK = 2;

    /** 对象键前缀，便于在同一空间内与其他用途隔离，也便于 IAM 策略按前缀授权。 */
    public String prefix = DEFAULT_PREFIX;
    /**
     * 上传成功后如何处理手机上的原始文件，取值见 {@link #DELETE_KEEP} 等。
     * 默认"总是删除"，即只保留云端副本。
     */
    public int deleteLocalMode = DELETE_ALWAYS;
    public String accessKey = "";
    public String secretKey = "";

    /** 配置是否完整到可以发起请求。任一项缺失时都应退回本地存储，而不是让附件功能报错。 */
    public boolean isUsable() {
        return enabled
                && !bucket.trim().isEmpty()
                && !region.trim().isEmpty()
                && !normalizedEndpoint().isEmpty()
                && !accessKey.trim().isEmpty()
                && !secretKey.trim().isEmpty();
    }

    public S3Signer.Credentials credentials() {
        return new S3Signer.Credentials(accessKey, secretKey);
    }

    public String normalizedEndpoint() {
        return S3Signer.trimEndpoint(endpoint);
    }

    /** 前缀统一成「无前导分隔符、有尾部分隔符」的形式，避免拼出 {@code notes//x} 这类键。 */
    public String normalizedPrefix() {
        String value = prefix == null ? "" : prefix.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (!value.isEmpty() && !value.endsWith("/")) {
            value = value + "/";
        }
        return value;
    }

    public CloudConfig copy() {
        CloudConfig copy = new CloudConfig();
        copy.enabled = enabled;
        copy.bucket = bucket;
        copy.region = region;
        copy.endpoint = endpoint;
        copy.prefix = prefix;
        copy.deleteLocalMode = deleteLocalMode;
        copy.accessKey = accessKey;
        copy.secretKey = secretKey;
        return copy;
    }
}
