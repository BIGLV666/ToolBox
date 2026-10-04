package com.example.toolbox.cloud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 笔记正文的云端同步。
 *
 * <p>正文（标题 + 正文文本）序列化成 JSON 后用 {@link Keyring} 派生的密钥加密，存到
 * {@code notes/meta/&lt;syncId&gt;.json}。密钥直接用 syncId 派生，所以不需要把任何密钥信息随对象一起存。</p>
 *
 * <p><b>备注不在这个 JSON 里</b>，因此不可能被上传；这也是"备注不上云"在实现层面的落点。</p>
 *
 * <p>加密凭据（keyring）以明文对象保存在 {@code notes/_meta/keyring.txt}：它本身只含被口令包裹的主密钥，
 * 不含可用密钥，所以可以直接放云端。换设备时先取它、再输口令，就能解出主密钥。</p>
 */
public final class NoteSyncClient {
    private static final String CONTENT_TYPE = "application/octet-stream";

    /** 一篇笔记的云端表示。 */
    public static final class Payload {
        public String syncId = "";
        public String title = "";
        public String body = "";
        /** 文件夹按名字同步：本地 folder_id 在别的设备上没有意义。 */
        public String folderName = "";
        public long createdAt;
        public long updatedAt;
        /** 附件清单：只带恢复笔记所需的元数据，不带文件内容。 */
        public final List<AttachmentRef> attachments = new ArrayList<>();
        /**
         * 下载时观察到的实际状态：true 表示云端那份是密文。
         *
         * <p>不写进 JSON 也不读自 JSON——它由下载端嗅探内容魔数得出。这样恢复出来的笔记能拿到准确的
         * 加密标记，而不是依赖"本地是否还存着这个标记"。</p>
         */
        public boolean encrypted;
        // ---- v2 起新增：删除传播。----
        /** 云端墓碑标记。true 表示这篇笔记已被删除，payload 本身仍完整保留，供回收站找回。 */
        public boolean deleted;
        /** 删除发生的时间（删除方时钟，毫秒）。合并时用它和本地 updatedAt 比，落实"删了又改可复活"。 */
        public long deletedAt;
        /** 删除方的设备标识，排查同步问题时定位是哪一端删的。 */
        public String deviceId = "";

        /**
         * 单个附件的云端引用。
         *
         * <p>附件内容仍存在对象存储里，这里只是"这篇笔记有哪些附件"的索引。没有它，换设备恢复时正文
         * 回来了、却不知道那些附件属于哪篇笔记，附件就等于丢失。</p>
         */
        public static final class AttachmentRef {
            public String name = "";
            public String mimeType = "";
            public String remoteKey = "";
            public String md5 = "";
            public String plainSha256 = "";
            public long size;
            public boolean encrypted;
        }

        /** 序列化成 JSON。字段名保持稳定，便于将来跨版本读取。 */
        public String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("v", 2);
                json.put("syncId", syncId);
                json.put("title", title == null ? "" : title);
                json.put("body", body == null ? "" : body);
                json.put("folderName", folderName == null ? "" : folderName);
                json.put("createdAt", createdAt);
                json.put("updatedAt", updatedAt);
                json.put("deleted", deleted);
                json.put("deletedAt", deletedAt);
                json.put("deviceId", deviceId == null ? "" : deviceId);
                JSONArray items = new JSONArray();
                for (AttachmentRef ref : attachments) {
                    if (ref.remoteKey == null || ref.remoteKey.isEmpty()) {
                        // 还没上传成功的附件没有云端位置，登记进去只会制造无用条目。
                        continue;
                    }
                    JSONObject item = new JSONObject();
                    item.put("name", ref.name == null ? "" : ref.name);
                    item.put("mimeType", ref.mimeType == null ? "" : ref.mimeType);
                    item.put("remoteKey", ref.remoteKey);
                    item.put("md5", ref.md5 == null ? "" : ref.md5);
                    item.put("plainSha256", ref.plainSha256 == null ? "" : ref.plainSha256);
                    item.put("size", ref.size);
                    item.put("encrypted", ref.encrypted);
                    items.put(item);
                }
                json.put("attachments", items);
                return json.toString();
            } catch (Exception error) {
                throw new IllegalStateException("正文序列化失败", error);
            }
        }

        public static Payload fromJson(String text) throws IOException {
            try {
                JSONObject json = new JSONObject(text);
                Payload payload = new Payload();
                payload.syncId = json.optString("syncId", "");
                payload.title = json.optString("title", "");
                payload.body = json.optString("body", "");
                payload.folderName = json.optString("folderName", "");
                payload.createdAt = json.optLong("createdAt", 0);
                payload.updatedAt = json.optLong("updatedAt", 0);
                // v1 的旧对象没有这些字段，opt 系列会给默认值，向后兼容。
                payload.deleted = json.optBoolean("deleted", false);
                payload.deletedAt = json.optLong("deletedAt", 0);
                payload.deviceId = json.optString("deviceId", "");
                JSONArray items = json.optJSONArray("attachments");
                for (int index = 0; items != null && index < items.length(); index++) {
                    JSONObject item = items.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    AttachmentRef ref = new AttachmentRef();
                    ref.name = item.optString("name", "");
                    ref.mimeType = item.optString("mimeType", "");
                    ref.remoteKey = item.optString("remoteKey", "");
                    ref.md5 = item.optString("md5", "");
                    ref.plainSha256 = item.optString("plainSha256", "");
                    ref.size = item.optLong("size", 0);
                    ref.encrypted = item.optBoolean("encrypted", false);
                    payload.attachments.add(ref);
                }
                return payload;
            } catch (Exception error) {
                throw new IOException("云端正文格式无法解析", error);
            }
        }
    }

    private final CloudConfig config;
    private final CloudClient client;
    private final File workDirectory;

    public NoteSyncClient(CloudConfig config, CloudClient client, File workDirectory) {
        this.config = config;
        this.client = client;
        this.workDirectory = workDirectory;
    }

    /**
     * 上传一篇笔记的正文。
     *
     * @param encrypted true 时用笔记密钥加密；false 时明文上传（内容自描述：没有魔数即视为明文）
     */
    public void upload(Keyring keyring, Payload payload, boolean encrypted) throws IOException {
        if (payload == null || payload.syncId == null || payload.syncId.isEmpty()) {
            throw new IOException("笔记缺少同步标识");
        }
        if (encrypted && keyring == null) {
            throw new IOException("这篇笔记设为加密上传，但尚未解锁口令");
        }
        File temporary = createWorkFile();
        try {
            byte[] plain = payload.toJson().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = new FileOutputStream(temporary)) {
                if (encrypted) {
                    NoteCipher.encrypt(new java.io.ByteArrayInputStream(plain), output,
                            keyring.noteKey(payload.syncId));
                } else {
                    output.write(plain);
                }
                output.flush();
            }
            // 临时文件里已是最终字节（密文或明文），因此这里都走"明文上传路径"，ETag 校验针对实际内容。
            client.putObject(metaKey(payload.syncId), temporary, CONTENT_TYPE, null, null);
        } finally {
            temporary.delete();
        }
    }

    /**
     * 下载一篇笔记的正文。
     *
     * <p>密钥按需要传：内容加密而没有密钥时会明确报错，不会把密文当 JSON 解析。</p>
     */
    public Payload download(Keyring keyring, String syncId) throws IOException {
        File temporary = createWorkFile();
        try {
            byte[] key = keyring == null ? null : keyring.noteKey(syncId);
            boolean decrypted = client.getObject(metaKey(syncId), temporary, null, key);
            Payload payload = Payload.fromJson(new String(readAll(temporary), StandardCharsets.UTF_8));
            payload.encrypted = decrypted;
            return payload;
        } finally {
            temporary.delete();
        }
    }

    /** 列出云端已有的笔记正文 syncId。 */
    public List<String> listSyncIds(int maxKeys) throws IOException {
        String prefix = ObjectKeys.noteMetaPrefix(config.normalizedPrefix());
        List<String> ids = new ArrayList<>();
        for (CloudClient.RemoteObject object : client.listObjects(prefix, maxKeys)) {
            String key = object.key;
            if (key == null || !key.startsWith(prefix) || !key.endsWith(".json")) {
                continue;
            }
            ids.add(key.substring(prefix.length(), key.length() - ".json".length()));
        }
        return ids;
    }

    /** 上传加密凭据。内容是明文文本，但其中只含被口令包裹的主密钥。 */
    public void uploadKeyring(String keyringText) throws IOException {
        File temporary = createWorkFile();
        try {
            try (OutputStream output = new FileOutputStream(temporary)) {
                output.write(keyringText.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            client.putObject(ObjectKeys.keyringKey(config.normalizedPrefix()), temporary,
                    "text/plain; charset=utf-8", null, null);
        } finally {
            temporary.delete();
        }
    }

    /** 取回加密凭据；云端还没有时返回空串。 */
    public String downloadKeyring() throws IOException {
        File temporary = createWorkFile();
        try {
            try {
                client.getObject(ObjectKeys.keyringKey(config.normalizedPrefix()), temporary, null, null);
            } catch (CloudException error) {
                if (error.httpStatus() == 404) {
                    return "";
                }
                throw error;
            }
            return new String(readAll(temporary), StandardCharsets.UTF_8);
        } finally {
            temporary.delete();
        }
    }

    private String metaKey(String syncId) {
        return ObjectKeys.noteMetaKey(config.normalizedPrefix(), syncId);
    }

    private File createWorkFile() throws IOException {
        if (workDirectory == null) {
            throw new IOException("未配置临时目录");
        }
        if (!workDirectory.exists() && !workDirectory.mkdirs()) {
            throw new IOException("无法创建临时目录");
        }
        return File.createTempFile("note-", ".tmp", workDirectory);
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
            }
            return buffer.toByteArray();
        }
    }
}
