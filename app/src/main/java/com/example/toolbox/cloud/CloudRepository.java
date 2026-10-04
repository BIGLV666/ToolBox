package com.example.toolbox.cloud;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Base64;

import com.example.toolbox.agent.security.ApiKeyCipher;
import com.example.toolbox.repository.ToolboxDatabaseHelper;

/**
 * 云同步配置与加密凭据的持久化。
 *
 * <p>配置存放在单行表 {@code note_cloud_settings} 中。三类敏感数据都不以明文落库：</p>
 * <ul>
 *   <li>访问密钥 AK/SK —— Keystore 加密后存 {@code encrypted_ak}/{@code encrypted_sk}；</li>
 *   <li>keyring 文本 —— 它本身只含"被口令包裹的主密钥"，不加密存放（换设备时还要靠它恢复）；</li>
 *   <li>已解锁的主密钥 —— Keystore 加密后存 {@code encrypted_mk}，用于本机免重复输入口令。</li>
 * </ul>
 *
 * <p>Keystore 密钥不可导出，换设备或恢复出厂后旧密文不可解。因此 AK/SK 与主密钥缓存都会失效，
 * 需要重新填写——但云端数据仍可凭口令恢复，这正是 keyring 单独存放的原因。</p>
 */
public final class CloudRepository {
    private static final String TABLE = "note_cloud_settings";
    private static final int ROW_ID = 1;

    private final ToolboxDatabaseHelper helper;
    private final ApiKeyCipher cipher = new ApiKeyCipher();

    public CloudRepository(Context context) {
        helper = new ToolboxDatabaseHelper(context);
    }

    /** 读取配置。凭据解密失败时返回空凭据而不是抛出，确保笔记页面始终可用。 */
    public CloudConfig load() {
        CloudConfig config = new CloudConfig();
        try (Cursor cursor = helper.getReadableDatabase().query(TABLE, null,
                "settings_id = ?", new String[]{String.valueOf(ROW_ID)}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) {
                return config;
            }
            config.enabled = intOf(cursor, "enabled") != 0;
            config.deleteLocalMode = deleteModeOf(intOf(cursor, "delete_local"));
            config.bucket = textOf(cursor, "bucket");
            config.region = emptyTo(textOf(cursor, "region"), CloudConfig.DEFAULT_REGION);
            config.endpoint = emptyTo(textOf(cursor, "endpoint"), CloudConfig.DEFAULT_ENDPOINT);
            config.prefix = emptyTo(textOf(cursor, "prefix"), CloudConfig.DEFAULT_PREFIX);
            config.accessKey = decrypt(textOf(cursor, "encrypted_ak"));
            config.secretKey = decrypt(textOf(cursor, "encrypted_sk"));
        }
        if (config.bucket.isEmpty()) {
            config.enabled = false;
        }
        return config;
    }

    /** 写入配置。空字符串表示清除该项凭据。 */
    public void save(CloudConfig config) {
        ContentValues values = new ContentValues();
        values.put("enabled", config.enabled ? 1 : 0);
        values.put("delete_local", config.deleteLocalMode);
        values.put("bucket", config.bucket == null ? "" : config.bucket.trim());
        values.put("region", emptyTo(config.region, CloudConfig.DEFAULT_REGION));
        values.put("endpoint", emptyTo(config.endpoint, CloudConfig.DEFAULT_ENDPOINT));
        values.put("prefix", config.prefix == null ? CloudConfig.DEFAULT_PREFIX : config.prefix.trim());
        values.put("encrypted_ak", encrypt(config.accessKey));
        values.put("encrypted_sk", encrypt(config.secretKey));
        values.put("ak_hint", hintOf(config.accessKey));
        write(values);
    }

    /** 仅切换"上传成功后如何处理本地文件"，避免为了改一个选项而重写凭据。 */
    public void saveDeleteLocalMode(int mode) {
        ContentValues values = new ContentValues();
        values.put("delete_local", deleteModeOf(mode));
        write(values);
    }

    // ---------- 加密凭据 ----------

    /** 云端/本地保存的 keyring 文本；尚未设置口令时为空串。 */
    public String loadKeyringText() {
        try (Cursor cursor = helper.getReadableDatabase().query(TABLE, new String[]{"keyring_text"},
                "settings_id = ?", new String[]{String.valueOf(ROW_ID)}, null, null, null, "1")) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return "";
            }
            return cursor.getString(0);
        }
    }

    public void saveKeyringText(String keyringText) {
        ContentValues values = new ContentValues();
        values.put("keyring_text", keyringText);
        write(values);
    }

    /** 本机缓存的已解锁主密钥。未缓存或无法解密（换了设备、改过口令）时返回 {@code null}。 */
    public byte[] loadCachedMasterKey() {
        String stored = readColumn("encrypted_mk");
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        try {
            return Base64.decode(cipher.decrypt(stored), Base64.NO_WRAP);
        } catch (RuntimeException error) {
            return null;
        }
    }

    public void saveCachedMasterKey(byte[] masterKey) {
        ContentValues values = new ContentValues();
        values.put("encrypted_mk", cipher.encrypt(Base64.encodeToString(masterKey, Base64.NO_WRAP)));
        write(values);
    }

    /** 清除本机缓存的主密钥（例如用户选择退出解锁状态）。 */
    public void clearCachedMasterKey() {
        ContentValues values = new ContentValues();
        values.putNull("encrypted_mk");
        write(values);
    }

    /**
     * 本设备标识。首次调用时生成并落库，之后不变。
     *
     * <p>同步 payload 里带上它，是为了在"两台设备先后改同一篇"这类问题上，日志能立刻指认是哪一端
     * 写入的版本——它不参与合并判定，只用于排查。</p>
     */
    public String loadOrCreateDeviceId() {
        String existing = readColumn("device_id");
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String generated = java.util.UUID.randomUUID().toString();
        ContentValues values = new ContentValues();
        values.put("device_id", generated);
        write(values);
        return generated;
    }

    // ---------- 内部工具 ----------

    private String readColumn(String column) {
        try (Cursor cursor = helper.getReadableDatabase().query(TABLE, new String[]{column},
                "settings_id = ?", new String[]{String.valueOf(ROW_ID)}, null, null, null, "1")) {
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return "";
            }
            return cursor.getString(0);
        }
    }

    private void write(ContentValues values) {
        int updated = helper.getWritableDatabase().update(TABLE, values,
                "settings_id = ?", new String[]{String.valueOf(ROW_ID)});
        if (updated == 0) {
            values.put("settings_id", ROW_ID);
            helper.getWritableDatabase().insertOrThrow(TABLE, null, values);
        }
    }

    private String encrypt(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return cipher.encrypt(value.trim());
        } catch (RuntimeException error) {
            // 加密器不可用时宁可不保存凭据，也不退化成明文写入数据库。
            throw new IllegalStateException("凭据加密失败，无法保存", error);
        }
    }

    private String decrypt(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return cipher.decrypt(value);
        } catch (RuntimeException error) {
            return "";
        }
    }

    /** 容错读取：未知取值一律回落到"总是删除"，与数据库默认值保持一致。 */
    private static int deleteModeOf(int stored) {
        if (stored == CloudConfig.DELETE_KEEP || stored == CloudConfig.DELETE_ASK) {
            return stored;
        }
        return CloudConfig.DELETE_ALWAYS;
    }

    private static String hintOf(String accessKey) {
        String value = accessKey == null ? "" : accessKey.trim();
        if (value.length() <= 4) {
            return value.isEmpty() ? "" : "****";
        }
        return value.substring(0, 4) + "****";
    }

    private static String textOf(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return "";
        }
        return cursor.getString(index);
    }

    private static int intOf(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return 0;
        }
        return cursor.getInt(index);
    }

    private static String emptyTo(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
