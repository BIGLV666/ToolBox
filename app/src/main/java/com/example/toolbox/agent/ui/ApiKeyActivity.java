package com.example.toolbox.agent.ui;

import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.toolbox.R;
import com.example.toolbox.agent.repository.AgentRepository;
import com.example.toolbox.agent.network.AgentClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 服务凭据管理界面，按“分组、Key、模型”关系组织配置。
 *
 * <p>列表只展示 Key 的脱敏提示；明文仅在用户点击显示、复制，或编辑时测试连接的明确操作中
 * 从仓储解密。数据库和网络操作均放到单线程后台执行器，视图和对话框只在 UI 线程更新。</p>
 */
public final class ApiKeyActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<AgentRepository.KeyGroup> groups = new ArrayList<>();
    private AgentRepository repository;
    private final AgentClient agentClient = new AgentClient();
    private LinearLayout groupContainer;
    private View empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_api_keys);
        repository = new AgentRepository(this);
        groupContainer = findViewById(R.id.key_groups);
        empty = findViewById(R.id.key_empty);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.key_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        findViewById(R.id.key_toolbar).setOnClickListener(view -> finish());
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.key_toolbar))
                .setNavigationOnClickListener(view -> finish());
        findViewById(R.id.add_key_group).setOnClickListener(view -> showAddGroup());
        findViewById(R.id.add_api_key).setOnClickListener(view -> {
            if (groups.isEmpty()) {
                showAddGroup();
            } else {
                showKeyForm(null, groups.get(0).id);
            }
        });
        loadGroups();
    }

    /** 在后台读取不含 Key 明文的分组树，并切回 UI 线程整体刷新列表。 */
    private void loadGroups() {
        executor.execute(() -> {
            try {
                List<AgentRepository.KeyGroup> loaded = repository.getGroups();
                runOnUiThread(() -> {
                    groups.clear();
                    groups.addAll(loaded);
                    renderGroups();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> showError(e));
            }
        });
    }

    private void renderGroups() {
        groupContainer.removeAllViews();
        empty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
        for (AgentRepository.KeyGroup group : groups) {
            MaterialCardView card = new MaterialCardView(this);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(14);
            card.setLayoutParams(cardParams);
            card.setRadius(dp(22));
            card.setCardElevation(0);
            card.setStrokeWidth(dp(1));
            card.setStrokeColor(getColor(R.color.toolbox_outline));

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(18), dp(16), dp(18), dp(16));
            LinearLayout header = new LinearLayout(this);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView title = new TextView(this);
            title.setText(group.name);
            title.setTextSize(18);
            title.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface));
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            MaterialButton add = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            add.setText(R.string.add_key);
            add.setOnClickListener(view -> showKeyForm(null, group.id));
            header.addView(add);
            content.addView(header);

            if (group.keys.isEmpty()) {
                TextView none = secondaryText(getString(R.string.api_keys_empty_desc));
                none.setPadding(0, dp(8), 0, 0);
                content.addView(none);
            }
            for (AgentRepository.ApiKeyConfig key : group.keys) {
                content.addView(createKeyRow(key));
            }
            card.addView(content);
            card.setOnLongClickListener(view -> {
                confirmDeleteGroup(group);
                return true;
            });
            groupContainer.addView(card);
        }
    }

    /**
     * 构造一个 Key 配置行。
     * 行内展示分组下的 Key 名称、脱敏提示、服务地址及关联模型，明文只由独立按钮按需读取。
     */
    private View createKeyRow(AgentRepository.ApiKeyConfig key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        row.setLayoutParams(params);
        row.setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainer));

        TextView name = new TextView(this);
        name.setText(key.name + "   " + key.hint);
        name.setTextSize(16);
        name.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface));
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(name);
        TextView url = secondaryText(key.baseUrl);
        url.setPadding(0, dp(4), 0, 0);
        row.addView(url);
        TextView models = secondaryText(String.join("  ·  ", key.models));
        models.setPadding(0, dp(7), 0, 0);
        row.addView(models);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.END);
        MaterialButton reveal = new MaterialButton(this);
        reveal.setText(R.string.reveal_api_key);
        reveal.setOnClickListener(view -> showApiKey(key, false));
        MaterialButton copy = new MaterialButton(this);
        copy.setText(R.string.copy_api_key);
        copy.setOnClickListener(view -> showApiKey(key, true));
        actions.addView(reveal);
        actions.addView(copy);
        row.addView(actions);
        row.setOnClickListener(view -> showKeyForm(key, key.groupId));
        row.setOnLongClickListener(view -> {
            confirmDeleteKey(key);
            return true;
        });
        return row;
    }

    /**
     * 按用户意图在后台解密 API Key。
     * 复制模式直接写入系统剪贴板；显示模式默认保持遮罩，用户可在对话框内切换显隐或复制。
     * 明文不写回视图列表，但进入剪贴板后由操作系统及其他获准访问剪贴板的组件管理。
     */
    private void showApiKey(AgentRepository.ApiKeyConfig config, boolean copyOnly) {
        executor.execute(() -> {
            try {
                String secret = repository.getApiKey(config.id);
                runOnUiThread(() -> {
                    if (copyOnly) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.api_key), secret));
                        Snackbar.make(findViewById(R.id.key_root), R.string.api_key_copied, Snackbar.LENGTH_SHORT).show();
                        return;
                    }
                    TextView value = new TextView(this);
                    value.setText(R.string.password_mask);
                    value.setTextIsSelectable(true);
                    value.setTextSize(16);
                    value.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface));
                    value.setPadding(dp(24), dp(8), dp(24), dp(8));
                    final boolean[] shown = {false};
                    AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(config.name).setView(value)
                            .setNeutralButton(R.string.show, null).setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.copy, (popup, which) -> {
                                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.api_key), secret));
                                Snackbar.make(findViewById(R.id.key_root), R.string.api_key_copied, Snackbar.LENGTH_SHORT).show();
                            }).create();
                    dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                            .setOnClickListener(button -> {
                                shown[0] = !shown[0];
                                value.setText(shown[0] ? secret : getString(R.string.password_mask));
                                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                                        .setText(shown[0] ? R.string.hide : R.string.show);
                            }));
                    dialog.show();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> showError(e));
            }
        });
    }

    private void showAddGroup() {
        TextInputLayout layout = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        int margin = dp(24);
        layout.setPadding(margin, 0, margin, 0);
        layout.setHint(R.string.group_name);
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(input);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.add_group).setView(layout)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = text(input);
            if (value.isEmpty()) {
                layout.setError(getString(R.string.required_name));
                return;
            }
            dialog.dismiss();
            executor.execute(() -> {
                try {
                    repository.addGroup(value);
                    runOnUiThread(this::loadGroups);
                } catch (RuntimeException e) {
                    runOnUiThread(() -> showError(e));
                }
            });
        }));
        dialog.show();
    }

    /**
     * 展示新增或编辑 Key 的表单，并维护其所属分组和模型集合。
     * 编辑时 API Key 留空表示沿用原密文；保存会由仓储在事务中同步更新 Key 与模型关系。
     */
    private void showKeyForm(AgentRepository.ApiKeyConfig existing, long defaultGroupId) {
        View form = getLayoutInflater().inflate(R.layout.dialog_api_key, null, false);
        AutoCompleteTextView groupInput = form.findViewById(R.id.key_group_input);
        TextInputEditText nameInput = form.findViewById(R.id.key_name_input);
        TextInputEditText urlInput = form.findViewById(R.id.base_url_input);
        TextInputEditText apiKeyInput = form.findViewById(R.id.api_key_input);
        TextInputEditText modelsInput = form.findViewById(R.id.models_input);
        AutoCompleteTextView protocolInput = form.findViewById(R.id.protocol_input);
        TextInputLayout apiKeyLayout = form.findViewById(R.id.api_key_layout);
        MaterialButton testConnection = form.findViewById(R.id.test_api_connection);
        List<String> groupNames = new ArrayList<>();
        String[] protocolLabels = getResources().getStringArray(R.array.agent_protocols);
        protocolInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line,
                protocolLabels));
        protocolInput.setText(existing != null && AgentRepository.PROTOCOL_RESPONSES.equals(existing.protocol)
                ? protocolLabels[1] : protocolLabels[0], false);
        int defaultPosition = 0;
        for (int i = 0; i < groups.size(); i++) {
            groupNames.add(groups.get(i).name);
            if (groups.get(i).id == defaultGroupId) defaultPosition = i;
        }
        groupInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, groupNames));
        groupInput.setText(groupNames.get(defaultPosition), false);
        if (existing != null) {
            nameInput.setText(existing.name);
            urlInput.setText(existing.baseUrl);
            modelsInput.setText(String.join("\n", existing.models));
            apiKeyLayout.setHint(R.string.api_key_keep);
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.add_key : R.string.edit)
                .setView(form).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null).create();
        // 测试连接只调用兼容协议的 /models：验证地址和鉴权，并让用户选择服务端返回的模型。
        testConnection.setOnClickListener(view -> {
            String url = text(urlInput);
            String enteredKey = text(apiKeyInput);
            if (url.isEmpty() || (enteredKey.isEmpty() && existing == null)) {
                showError(new IllegalArgumentException("请先填写 Base URL 和 API Key"));
                return;
            }
            testConnection.setEnabled(false);
            testConnection.setText(R.string.testing_connection);
            // 网络请求和必要的旧 Key 解密均在后台执行，按钮状态与模型选择框只在 UI 线程更新。
            executor.execute(() -> {
                try {
                    String key = enteredKey.isEmpty() ? repository.getApiKey(existing.id) : enteredKey;
                    String normalized = normalizeUrl(url);
                    List<String> fetched = agentClient.fetchModels(normalized, key);
                    runOnUiThread(() -> {
                        testConnection.setEnabled(true);
                        testConnection.setText(R.string.test_connection);
                        if (fetched.isEmpty()) {
                            Snackbar.make(findViewById(R.id.key_root), R.string.connection_no_models, Snackbar.LENGTH_LONG).show();
                        } else {
                            Snackbar.make(findViewById(R.id.key_root),
                                    getString(R.string.connection_success, fetched.size()), Snackbar.LENGTH_LONG).show();
                            showFetchedModels(fetched, modelsInput);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        testConnection.setEnabled(true);
                        testConnection.setText(R.string.test_connection);
                        showError(e);
                    });
                }
            });
        });
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            int selected = groupNames.indexOf(groupInput.getText().toString());
            String secret = text(apiKeyInput);
            if (selected < 0 || text(nameInput).isEmpty() || text(urlInput).isEmpty()
                    || (existing == null && secret.isEmpty()) || parseModels(text(modelsInput)).isEmpty()) {
                showError(new IllegalArgumentException("请填写分组、名称、地址、API Key 和至少一个模型"));
                return;
            }
            long groupId = groups.get(selected).id;
            dialog.dismiss();
            executor.execute(() -> {
                try {
                    if (existing == null) {
                        repository.addKey(groupId, text(nameInput), text(urlInput), secret,
                                parseModels(text(modelsInput)), protocolInput.getText().toString().equals(protocolLabels[1])
                                        ? AgentRepository.PROTOCOL_RESPONSES : AgentRepository.PROTOCOL_CHAT);
                    } else {
                        repository.updateKey(existing.id, groupId, text(nameInput), text(urlInput), secret,
                                parseModels(text(modelsInput)), protocolInput.getText().toString().equals(protocolLabels[1])
                                        ? AgentRepository.PROTOCOL_RESPONSES : AgentRepository.PROTOCOL_CHAT);
                    }
                    runOnUiThread(() -> {
                        Snackbar.make(findViewById(R.id.key_root), R.string.key_saved, Snackbar.LENGTH_SHORT).show();
                        loadGroups();
                    });
                } catch (RuntimeException e) {
                    runOnUiThread(() -> showError(e));
                }
            });
        }));
        dialog.show();
    }

    private void confirmDeleteGroup(AgentRepository.KeyGroup group) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_group_title)
                .setMessage(R.string.delete_group_desc).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> executor.execute(() -> {
                    repository.deleteGroup(group.id);
                    runOnUiThread(this::loadGroups);
                })).show();
    }

    /**
     * 将测试连接得到的模型列表作为候选项展示。
     * 这一步只更新表单文本，用户仍需点击主表单的保存按钮才会持久化模型关系。
     */
    private void showFetchedModels(List<String> fetched, TextInputEditText modelsInput) {
        String[] names = fetched.toArray(new String[0]);
        List<String> current = parseModels(text(modelsInput));
        boolean[] selected = new boolean[names.length];
        for (int i = 0; i < names.length; i++) selected[i] = current.isEmpty() || current.contains(names[i]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.model_list_title)
                .setMultiChoiceItems(names, selected, (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < names.length; i++) if (selected[i]) chosen.add(names[i]);
                    modelsInput.setText(String.join("\n", chosen));
                }).show();
    }

    private void confirmDeleteKey(AgentRepository.ApiKeyConfig key) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_key_title)
                .setMessage(key.name).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> executor.execute(() -> {
                    repository.deleteKey(key.id);
                    runOnUiThread(this::loadGroups);
                })).show();
    }

    private TextView secondaryText(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(13);
        text.setTextColor(getColor(R.color.toolbox_muted));
        return text;
    }

    private int resolveColor(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    private static List<String> parseModels(String value) {
        List<String> result = new ArrayList<>();
        for (String item : value.split("[,\\n]")) {
            String model = item.trim();
            if (!model.isEmpty() && !result.contains(model)) result.add(model);
        }
        return result;
    }

    private static String normalizeUrl(String value) {
        String url = value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/chat/completions")) url = url.substring(0, url.length() - 17);
        return url;
    }

    private static String text(TextView input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showError(Exception exception) {
        String message = exception.getMessage();
        Snackbar.make(findViewById(R.id.key_root),
                message == null ? getString(R.string.operation_failed) : message, Snackbar.LENGTH_LONG).show();
    }

    @Override
    /** 页面销毁时取消可能阻塞的测试连接并停止后台执行器。 */
    protected void onDestroy() {
        agentClient.cancel();
        executor.shutdownNow();
        super.onDestroy();
    }
}
