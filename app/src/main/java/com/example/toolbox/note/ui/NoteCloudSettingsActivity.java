package com.example.toolbox.note.ui;

import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.toolbox.R;
import com.example.toolbox.cloud.CloudClient;
import com.example.toolbox.cloud.CloudConfig;
import com.example.toolbox.cloud.CloudException;
import com.example.toolbox.cloud.CloudRepository;
import com.example.toolbox.cloud.Keyring;
import com.example.toolbox.cloud.NoteSyncClient;
import com.example.toolbox.note.NoteRepository;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 云同步设置页：空间与凭据、加密口令、本地文件处理策略、从云端恢复。
 *
 * <p>请求端点由区域推导并只读展示：区域参与签名密钥派生而端点决定请求地址，两者不一致时服务端返回
 * {@code SignatureDoesNotMatch}，把端点交给用户手填等于把最容易踩的坑留给用户。</p>
 *
 * <p>口令按钮承担三种语义，按当前状态自动选择：尚未设置口令时创建；有 keyring 但没有本机缓存时解锁；
 * 两者都有时用新口令重新包裹主密钥（此时不需要旧口令，因为本机缓存里已有主密钥）。</p>
 */
public final class NoteCloudSettingsActivity extends AppCompatActivity {
    private static final String TAG = "NoteCloudSettings";
    private static final int MIN_PASSPHRASE_LENGTH = 8;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<CloudConfig.Region> regions = new ArrayList<>();
    private CloudRepository repository;
    private MaterialSwitch enabledSwitch;
    private AutoCompleteTextView regionInput;
    private AutoCompleteTextView deleteModeInput;
    private TextInputEditText bucketInput;
    private TextInputEditText prefixInput;
    private TextInputEditText accessKeyInput;
    private TextInputEditText secretKeyInput;
    private TextInputEditText passphraseInput;
    private TextInputEditText passphraseConfirmInput;
    private TextView endpointValue;
    private TextView status;
    private TextView passphraseStatus;
    private String keyringText = "";
    private byte[] cachedMasterKey;
    private Keyring keyring;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_note_cloud_settings);
        repository = new CloudRepository(this);
        enabledSwitch = findViewById(R.id.cloud_enabled);
        bucketInput = findViewById(R.id.cloud_bucket_input);
        prefixInput = findViewById(R.id.cloud_prefix_input);
        accessKeyInput = findViewById(R.id.cloud_ak_input);
        secretKeyInput = findViewById(R.id.cloud_sk_input);
        regionInput = findViewById(R.id.cloud_region_input);
        deleteModeInput = findViewById(R.id.cloud_delete_mode_input);
        passphraseInput = findViewById(R.id.cloud_passphrase_input);
        passphraseConfirmInput = findViewById(R.id.cloud_passphrase_confirm_input);
        endpointValue = findViewById(R.id.cloud_endpoint_value);
        status = findViewById(R.id.cloud_status);
        passphraseStatus = findViewById(R.id.cloud_passphrase_status);
        ((MaterialToolbar) findViewById(R.id.cloud_settings_toolbar))
                .setNavigationOnClickListener(view -> finish());

        regions.clear();
        for (CloudConfig.Region region : CloudConfig.REGIONS) {
            regions.add(region);
        }
        regionInput.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, regions));
        regionInput.setOnItemClickListener((parent, view, position, id) -> showEndpoint());
        deleteModeInput.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, deleteModeLabels()));

        findViewById(R.id.cloud_test).setOnClickListener(view -> testConnection());
        findViewById(R.id.cloud_passphrase_save).setOnClickListener(view -> applyPassphrase());
        findViewById(R.id.cloud_restore).setOnClickListener(view -> restore());
        findViewById(R.id.cloud_save).setOnClickListener(view -> save());
        load();
    }

    private String[] deleteModeLabels() {
        return new String[]{
                getString(R.string.cloud_delete_keep),
                getString(R.string.cloud_delete_always),
                getString(R.string.cloud_delete_ask)};
    }

    private void load() {
        executor.execute(() -> {
            CloudConfig config = repository.load();
            String storedKeyring = repository.loadKeyringText();
            byte[] masterKey = repository.loadCachedMasterKey();
            runOnUiThread(() -> {
                bind(config);
                bindKeyring(storedKeyring, masterKey);
            });
        });
    }

    private void bind(CloudConfig config) {
        enabledSwitch.setChecked(config.enabled);
        bucketInput.setText(config.bucket);
        prefixInput.setText(config.prefix);
        accessKeyInput.setText(config.accessKey);
        secretKeyInput.setText(config.secretKey);
        regionInput.setText(String.valueOf(CloudConfig.regionOf(config.region)), false);
        deleteModeInput.setText(deleteModeLabels()[modeIndex(config.deleteLocalMode)], false);
        showEndpoint();
        status.setText(config.accessKey.isEmpty() ? ""
                : getString(R.string.cloud_credential_saved, hintOf(config.accessKey)));
    }

    /**
     * 绑定口令状态。本机缓存了主密钥就说明可以免输入使用：此时按钮是"改口令"，
     * 否则是需要先解锁。
     */
    private void bindKeyring(String storedKeyring, byte[] masterKey) {
        keyringText = storedKeyring == null ? "" : storedKeyring;
        cachedMasterKey = masterKey;
        keyring = null;
        if (keyringText.isEmpty()) {
            passphraseStatus.setText(R.string.cloud_passphrase_absent);
            ((MaterialButton) findViewById(R.id.cloud_passphrase_save))
                    .setText(R.string.cloud_passphrase_set);
            return;
        }
        if (masterKey != null) {
            try {
                keyring = Keyring.withMasterKey(keyringText, masterKey);
                passphraseStatus.setText(R.string.cloud_passphrase_ready);
                ((MaterialButton) findViewById(R.id.cloud_passphrase_save))
                        .setText(R.string.cloud_passphrase_change);
                return;
            } catch (Keyring.WrongPasswordException error) {
                keyring = null;
            }
        }
        passphraseStatus.setText(R.string.cloud_passphrase_absent);
        ((MaterialButton) findViewById(R.id.cloud_passphrase_save))
                .setText(R.string.cloud_passphrase_unlock);
    }

    /** 口令按钮：按当前状态决定是创建、解锁还是更换。 */
    private void applyPassphrase() {
        String password = text(passphraseInput);
        String confirm = text(passphraseConfirmInput);
        if (password.length() < MIN_PASSPHRASE_LENGTH) {
            passphraseStatus.setText(R.string.cloud_passphrase_too_short);
            return;
        }
        final boolean creating = keyringText.isEmpty();
        final boolean changing = keyring != null;
        if ((creating || changing) && !password.equals(confirm)) {
            passphraseStatus.setText(R.string.cloud_passphrase_mismatch);
            return;
        }
        passphraseStatus.setText(R.string.cloud_testing);
        executor.execute(() -> {
            String message;
            boolean ok = true;
            try {
                if (creating) {
                    Keyring created = Keyring.create(password.toCharArray());
                    keyringText = created.toText();
                    cachedMasterKey = created.masterKey();
                    keyring = created;
                    repository.saveKeyringText(keyringText);
                    repository.saveCachedMasterKey(cachedMasterKey);
                    message = getString(R.string.cloud_passphrase_saved);
                } else if (changing) {
                    // 本机已有主密钥，改口令只需重新包裹，云端已上传的正文无需重传。
                    Keyring rewrapped = keyring.rewrap(password.toCharArray());
                    keyringText = rewrapped.toText();
                    cachedMasterKey = rewrapped.masterKey();
                    keyring = rewrapped;
                    repository.saveKeyringText(keyringText);
                    repository.saveCachedMasterKey(cachedMasterKey);
                    message = getString(R.string.cloud_passphrase_changed);
                } else {
                    Keyring unlocked = Keyring.unlock(password.toCharArray(), keyringText);
                    cachedMasterKey = unlocked.masterKey();
                    keyring = unlocked;
                    repository.saveCachedMasterKey(cachedMasterKey);
                    message = getString(R.string.cloud_passphrase_ready);
                }
            } catch (Keyring.WrongPasswordException error) {
                ok = false;
                message = getString(R.string.cloud_passphrase_wrong);
            }
            final boolean published = ok;
            final String result = message;
            runOnUiThread(() -> {
                passphraseInput.setText("");
                passphraseConfirmInput.setText("");
                bindKeyring(keyringText, cachedMasterKey);
                passphraseStatus.setText(result);
                if (published) {
                    // 把 keyring 同步到云端，换设备时才有东西可取。
                    uploadKeyringQuietly();
                }
            });
        });
    }

    private void uploadKeyringQuietly() {
        CloudConfig config = collect();
        if (!config.isUsable() || keyringText.isEmpty()) {
            return;
        }
        final String text = keyringText;
        executor.execute(() -> {
            CloudClient client = new CloudClient(config, workRoot());
            try {
                new NoteSyncClient(config, client, workRoot()).uploadKeyring(text);
            } catch (IOException ignored) {
                // 上传失败不打断设置流程：本地已保存，下次联网再传。
            }
        });
    }

    /** 从云端取回正文（标题与正文文本），按 syncId 合并。备注不在云端，因此不受影响。 */
    private void restore() {
        CloudConfig config = collect();
        if (!config.isUsable()) {
            status.setText(R.string.cloud_incomplete);
            return;
        }
        // 不再要求必须先解锁：明文笔记无需口令即可恢复；加密的那些会各自失败并计入结果。
        status.setText(R.string.cloud_restore_running);
        findViewById(R.id.cloud_restore).setEnabled(false);
        final Keyring unlocked = keyring;
        executor.execute(() -> {
            int inserted = 0;
            int updated = 0;
            int emptyBodies = 0;
            int recoveredAttachments = 0;
            int failed = 0;
            String message;
            try {
                CloudClient client = new CloudClient(config, workRoot());
                NoteSyncClient sync = new NoteSyncClient(config, client, workRoot());
                NoteRepository notes = new NoteRepository(this);
                List<String> syncIds = sync.listSyncIds(500);
                for (String syncId : syncIds) {
                    NoteSyncClient.Payload payload;
                    try {
                        payload = sync.download(unlocked, syncId);
                    } catch (IOException error) {
                        // 单篇失败不中断整体：云端内容加密而本机未解锁时会走到这里，
                        // 但同一批次里的明文笔记应当照常恢复。
                        failed++;
                        Log.w(TAG, "恢复失败 syncId=" + syncId + " " + error.getMessage());
                        continue;
                    }
                    Long folderId = notes.findFolderIdByName(payload.folderName);
                    boolean existed = notes.findBySyncId(syncId) != null;
                    if (notes.mergeFromCloud(toNote(payload), folderId)) {
                        if (existed) {
                            updated++;
                        } else {
                            inserted++;
                        }
                    }
                    // 附件清单是 v11 之后才随正文一起上传的，老对象里没有；没有清单自然无从恢复。
                    recoveredAttachments += notes.recoverAttachments(syncId,
                            toAttachments(payload, config.bucket));
                    // 正文为空通常意味着文本还记在"备注"里——备注按设计不上云，所以这里恢复不出来。
                    if (payload.body == null || payload.body.trim().isEmpty()) {
                        emptyBodies++;
                    }
                    Log.i(TAG, "恢复笔记 syncId=" + syncId
                            + " 标题字数=" + payload.title.length()
                            + " 正文字数=" + (payload.body == null ? 0 : payload.body.length())
                            + " 附件数=" + payload.attachments.size()
                            + " 加密=" + payload.encrypted);
                }
                message = syncIds.isEmpty() ? getString(R.string.cloud_restore_none)
                        : getString(R.string.cloud_restore_done, inserted, updated);
                if (emptyBodies > 0) {
                    message = message + "\n" + getString(R.string.cloud_restore_empty_body, emptyBodies);
                }
                if (recoveredAttachments > 0) {
                    message = message + "\n" + getString(R.string.cloud_restore_attachments, recoveredAttachments);
                }
                if (failed > 0) {
                    message = message + "\n" + getString(R.string.cloud_restore_failed_notes, failed);
                }
            } catch (IOException error) {
                message = getString(R.string.cloud_restore_failed, describe(error));
            }
            final String result = message;
            runOnUiThread(() -> {
                status.setText(result);
                findViewById(R.id.cloud_restore).setEnabled(true);
            });
        });
    }

    private static NoteRepository.Note toNote(NoteSyncClient.Payload payload) {
        NoteRepository.Note note = new NoteRepository.Note();
        note.title = payload.title;
        note.body = payload.body;
        note.content = "";
        note.syncId = payload.syncId;
        note.createdAt = payload.createdAt;
        note.updatedAt = payload.updatedAt;
        note.syncState = NoteRepository.Note.SYNCED;
        // 加密标记取自下载时对内容的嗅探结果，而不是本地残留的旧值。
        note.encrypted = payload.encrypted
                ? NoteRepository.Note.ENCRYPTION_ENCRYPTED
                : NoteRepository.Note.ENCRYPTION_PLAIN;
        return note;
    }

    /**
     * 把云端附件清单还原成附件记录。
     *
     * <p>只重建元数据：{@code uri} 与 {@code localPath} 都留空，表示"文件只在云端"，打开时按需下载。</p>
     */
    private static List<NoteRepository.Attachment> toAttachments(NoteSyncClient.Payload payload, String bucket) {
        List<NoteRepository.Attachment> attachments = new ArrayList<>();
        for (NoteSyncClient.Payload.AttachmentRef ref : payload.attachments) {
            if (ref.remoteKey.isEmpty()) {
                continue;
            }
            NoteRepository.Attachment attachment = new NoteRepository.Attachment(null, 0, "",
                    ref.name, ref.mimeType, ref.size, null, ref.remoteKey, bucket, ref.md5,
                    NoteRepository.Attachment.STATE_UPLOADED);
            attachment.encrypted = ref.encrypted;
            attachment.plainSha256 = ref.plainSha256;
            attachments.add(attachment);
        }
        return attachments;
    }

    private void testConnection() {
        CloudConfig config = collect();
        if (config.bucket.isEmpty() || config.accessKey.isEmpty() || config.secretKey.isEmpty()) {
            status.setText(R.string.cloud_incomplete);
            return;
        }
        status.setText(R.string.cloud_testing);
        findViewById(R.id.cloud_test).setEnabled(false);
        executor.execute(() -> {
            String message;
            try {
                List<CloudClient.RemoteObject> objects =
                        new CloudClient(config, workRoot()).probe();
                message = getString(R.string.cloud_test_ok, objects.size());
            } catch (IOException error) {
                message = getString(R.string.cloud_test_failed, describe(error));
            }
            final String result = message;
            runOnUiThread(() -> {
                status.setText(result);
                findViewById(R.id.cloud_test).setEnabled(true);
            });
        });
    }

    private void save() {
        CloudConfig config = collect();
        if (config.enabled
                && (config.bucket.isEmpty() || config.accessKey.isEmpty() || config.secretKey.isEmpty())) {
            status.setText(R.string.cloud_incomplete);
            return;
        }
        executor.execute(() -> {
            repository.save(config);
            runOnUiThread(() -> {
                Snackbar.make(findViewById(R.id.cloud_settings_root), R.string.cloud_saved,
                        Snackbar.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    /** 读取表单当前值。测试、保存、恢复三条路径共用，保证校验的是同一份输入。 */
    private CloudConfig collect() {
        CloudConfig config = new CloudConfig();
        config.enabled = enabledSwitch.isChecked();
        config.deleteLocalMode = selectedDeleteMode();
        config.bucket = text(bucketInput);
        config.prefix = text(prefixInput);
        config.accessKey = text(accessKeyInput);
        config.secretKey = text(secretKeyInput);
        CloudConfig.Region region = selectedRegion();
        config.region = region.id;
        config.endpoint = region.endpoint;
        return config;
    }

    private int selectedDeleteMode() {
        String selected = deleteModeInput.getText() == null ? "" : deleteModeInput.getText().toString();
        String[] labels = deleteModeLabels();
        for (int index = 0; index < labels.length; index++) {
            if (labels[index].equals(selected)) {
                return index;
            }
        }
        return CloudConfig.DELETE_ALWAYS;
    }

    private static int modeIndex(int mode) {
        if (mode == CloudConfig.DELETE_KEEP || mode == CloudConfig.DELETE_ASK) {
            return mode;
        }
        return CloudConfig.DELETE_ALWAYS;
    }

    private CloudConfig.Region selectedRegion() {
        String selected = regionInput.getText() == null ? "" : regionInput.getText().toString().trim();
        for (CloudConfig.Region region : regions) {
            if (String.valueOf(region).equals(selected)) {
                return region;
            }
        }
        return CloudConfig.regionOf(CloudConfig.DEFAULT_REGION);
    }

    private void showEndpoint() {
        endpointValue.setText(getString(R.string.cloud_endpoint_value, selectedRegion().endpoint));
    }

    private File workRoot() {
        return new File(getCacheDir(), "note_cloud");
    }

    /** 把服务端错误码翻译成可操作的提示，而不是把 XML 错误原文抛给用户。 */
    private String describe(IOException error) {
        if (error instanceof CloudException) {
            CloudException failure = (CloudException) error;
            String code = failure.code();
            if ("InvalidAccessKeyId".equals(code)) {
                return getString(R.string.cloud_error_access_key);
            }
            if ("SignatureDoesNotMatch".equals(code)) {
                return getString(R.string.cloud_error_signature);
            }
            if ("AccessDenied".equals(code) || "error-notPremium".equals(code)) {
                return getString(R.string.cloud_error_denied);
            }
            if ("NoSuchBucket".equals(code) || "NotFound".equals(code)) {
                return getString(R.string.cloud_error_bucket);
            }
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }

    private static String text(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String hintOf(String accessKey) {
        String value = accessKey == null ? "" : accessKey.trim();
        if (value.length() <= 4) {
            return value.isEmpty() ? "" : "****";
        }
        return value.substring(0, 4) + "****";
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
