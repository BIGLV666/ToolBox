package com.example.toolbox.agent.ui;

import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.method.LinkMovementMethod;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.toolbox.R;
import com.example.toolbox.agent.network.AgentClient;
import com.example.toolbox.agent.repository.AgentRepository;
import com.example.toolbox.note.MarkdownRenderer;
import com.example.toolbox.Password.model.Password;
import com.example.toolbox.Password.service.PasswordService;
import com.example.toolbox.util.SwipeBackHelper;
import com.example.toolbox.util.WordParse;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Agent 对话界面，协调模型选择、流式响应、本机工具提示和会话持久化。
 *
 * <p>数据库与网络工作由单线程执行器串行处理，密码明文读取使用独立的本地执行器；所有视图
 * 更新显式切回 UI 线程。模型返回的密码检索结果只有元数据，密码明文必须由用户点击具体条目后
 * 在本机读取，并且不会回填模型上下文。</p>
 */
public final class AgentActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService localExecutor = Executors.newSingleThreadExecutor();
    private final AgentClient client = new AgentClient();
    private final List<AgentRepository.ModelOption> models = new ArrayList<>();
    private final List<UploadedWord> pendingFiles = new ArrayList<>();
    private final ActivityResultLauncher<String[]> wordPicker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), this::onWordFilesSelected);
    private AgentRepository repository;
    private PasswordService passwordService;
    private AgentMessageAdapter adapter;
    private TextView selectedModelName;
    private TextView selectedModelSource;
    private TextInputEditText input;
    private TextView status;
    private View empty;
    private FloatingActionButton send;
    private AgentRepository.ModelOption selectedModel;
    private long conversationId;
    private boolean generating;
    private long generationId;
    private boolean restored;
    private SwipeBackHelper.Controller swipeBack;
    private MaterialSwitch smartSearchSwitch;
    private AgentRepository.AgentSettings settings;
    private RecyclerView messageList;
    private View agentControls;
    private TextView controlsToggle;
    private boolean controlsExpanded;
    private boolean followStreaming = true;
    private boolean storageAccessPromptShown;
    private View attachmentStrip;
    private LinearLayout attachmentList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_agent);
        repository = new AgentRepository(this);
        passwordService = new PasswordService(this);
        bindViews();
        configureInsets();
        swipeBack = SwipeBackHelper.create(findViewById(R.id.agent_root), this::finish);
    }

    private void bindViews() {
        selectedModelName = findViewById(R.id.selected_model_name);
        selectedModelSource = findViewById(R.id.selected_model_source);
        input = findViewById(R.id.agent_input);
        status = findViewById(R.id.agent_status);
        empty = findViewById(R.id.agent_empty);
        send = findViewById(R.id.agent_send);
        attachmentStrip = findViewById(R.id.agent_attachment_strip);
        attachmentList = findViewById(R.id.agent_attachment_list);
        smartSearchSwitch = findViewById(R.id.smart_search_switch);
        adapter = new AgentMessageAdapter();
        messageList = findViewById(R.id.agent_messages);
        messageList.setLayoutManager(new LinearLayoutManager(this));
        messageList.setAdapter(adapter);
        messageList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) followStreaming = false;
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(1)) {
                    followStreaming = true;
                }
            }
        });
        agentControls = findViewById(R.id.agent_controls);
        controlsToggle = findViewById(R.id.agent_controls_toggle);
        controlsToggle.setOnClickListener(view -> setControlsExpanded(!controlsExpanded));
        setControlsExpanded(false);

        MaterialToolbar toolbar = findViewById(R.id.agent_toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_new_chat) {
                startNewConversation();
                return true;
            }
            if (item.getItemId() == R.id.action_history) {
                showHistory();
                return true;
            }
            if (item.getItemId() == R.id.action_agent_settings) {
                startActivity(new Intent(this, AgentSettingsActivity.class));
                return true;
            }
            if (item.getItemId() == R.id.action_compress_memory) {
                compressMemoryNow();
                return true;
            }
            return false;
        });
        findViewById(R.id.manage_keys).setOnClickListener(view ->
                startActivity(new Intent(this, ApiKeyActivity.class)));
        findViewById(R.id.agent_attach).setOnClickListener(view -> {
            if (generating) return;
            wordPicker.launch(new String[]{
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            });
        });
        send.setOnClickListener(view -> {
            if (generating) client.cancel(); else sendMessage();
        });
        input.setOnFocusChangeListener((view, focused) -> {
            if (focused) view.postDelayed(this::scrollToBottom, 250);
        });
        findViewById(R.id.model_selector).setOnClickListener(view -> showModelPicker());
        smartSearchSwitch.setOnCheckedChangeListener((button, checked) -> updateControlsSummary());
    }

    private void configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.agent_root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            if (ime.bottom > 0) view.post(this::scrollToBottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repository != null) {
            loadModels();
            showStorageAccessPrompt();
        }
    }

    private void showStorageAccessPrompt() {
        if (repository.hasBroadStorageAccess() || storageAccessPromptShown) return;
        storageAccessPromptShown = true;
        Snackbar.make(findViewById(R.id.agent_root), R.string.agent_storage_access_needed,
                        Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.grant_all_files, view -> requestAllFilesAccess())
                .show();
    }

    private void requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException error) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            return;
        }
        androidx.core.app.ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2101);
    }

    /**
     * 在后台展开分组、Key、模型配置，并在 UI 线程刷新选择状态。
     * 首次获得可用模型后恢复最近会话，后续从 Key 管理页返回时仅刷新配置并尽量保留原模型。
     */
    private void loadModels() {
        executor.execute(() -> {
            try {
                List<AgentRepository.ModelOption> loaded = repository.getModelOptions();
                AgentRepository.AgentSettings loadedSettings = repository.getSettings();
                runOnUiThread(() -> {
                    settings = loadedSettings;
                    smartSearchSwitch.setChecked(loadedSettings.smartSearch);
                    models.clear();
                    models.addAll(loaded);
                    if (models.isEmpty()) {
                        selectedModel = null;
                        selectedModelName.setText(R.string.agent_select_model);
                        selectedModelSource.setText(R.string.agent_no_model);
                    } else {
                        AgentRepository.ModelOption keep = selectedModel;
                        selectedModel = findModel(loadedSettings.chatModelId, models.get(0));
                        if (keep != null) {
                            for (AgentRepository.ModelOption option : models) {
                                if (option.id == keep.id) selectedModel = option;
                            }
                        }
                        showSelectedModel();
                        if (!restored) {
                            restored = true;
                            restoreLatestConversation();
                        }
                    }
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> showError(e));
            }
        });
    }

    /**
     * 提交用户输入并启动一次可取消的 Agent 运行。
     *
     * <p>用户消息先持久化，再读取有限条近期完整消息作为上下文；SSE 文本增量通过监听器逐步
     * 更新占位的助手消息，最终完整答案才写入数据库。{@code generationId} 将回调与发起时的请求
     * 绑定，取消、新建或切换会话后，旧请求即使迟到也不能继续修改当前界面。</p>
     */
    private void sendMessage() {
        String prompt = input.getText() == null ? "" : input.getText().toString().trim();
        if (selectedModel == null) {
            Snackbar.make(findViewById(R.id.agent_root), R.string.agent_no_model, Snackbar.LENGTH_LONG)
                    .setAction(R.string.agent_keys, view -> startActivity(new Intent(this, ApiKeyActivity.class))).show();
            return;
        }
        if (prompt.isEmpty() && pendingFiles.isEmpty()) return;
        if (prompt.isEmpty()) prompt = getString(R.string.analyze_uploaded_word);
        final List<UploadedWord> requestFiles = new ArrayList<>(pendingFiles);
        final List<AgentClient.FileAttachment> attachments = new ArrayList<>();
        for (UploadedWord file : requestFiles) {
            attachments.add(new AgentClient.FileAttachment(file.id, file.name, file.mimeType));
        }
        String visiblePrompt = prompt;
        if (!requestFiles.isEmpty()) {
            StringBuilder names = new StringBuilder(prompt).append("\n\n").append(getString(R.string.uploaded_files));
            for (UploadedWord file : requestFiles) names.append("\n- ").append(file.name);
            visiblePrompt = names.toString();
        }
        input.setText("");
        pendingFiles.clear();
        renderPendingFiles();
        generating = true;
        followStreaming = true;
        final long requestId = ++generationId;
        final AgentRepository.AgentSettings requestSettings = settings == null
                ? new AgentRepository.AgentSettings(false, 0, 0, 0, 3200, "") : settings;
        final boolean requestSmartSearch = smartSearchSwitch.isChecked();
        final AgentRepository.ModelOption chatModel = selectedModel;
        final AgentRepository.ModelOption requestModel = requestSmartSearch
                ? findModel(requestSettings.searchModelId, chatModel) : chatModel;
        final AgentRepository.ModelOption compressionModel = findModel(
                requestSettings.compressionModelId, chatModel);
        send.setImageResource(android.R.drawable.ic_media_pause);
        empty.setVisibility(View.GONE);
        adapter.add(new UiMessage("user", visiblePrompt));
        UiMessage assistant = new UiMessage("assistant", "");
        adapter.add(assistant);
        scrollToBottom();
        final int[] usage = {0, 0, 0};

        // 网络、数据库和工具循环均不占用 UI 线程；监听器只负责把可见状态投递回来。
        final String requestPrompt = visiblePrompt;
        executor.execute(() -> {
            try {
                if (conversationId == 0) {
                    String title = requestPrompt.length() > 28 ? requestPrompt.substring(0, 28) : requestPrompt;
                    conversationId = repository.createConversation(title, requestModel.id);
                }
                final long requestConversationId = conversationId;
                repository.addMessage(requestConversationId, "user", requestPrompt, "complete", null, null);
                List<AgentRepository.ChatMessage> history = repository.getRecentMessages(requestConversationId, 100);
                AgentRepository.ConversationContext context = repository.getConversationContext(requestConversationId);
                if (context.summarizedMessageId > 0) {
                    List<AgentRepository.ChatMessage> unsummarized = new ArrayList<>();
                    for (AgentRepository.ChatMessage message : history) {
                        if (message.id > context.summarizedMessageId) unsummarized.add(message);
                    }
                    history = unsummarized;
                }
                int estimated = AgentClient.estimateTokens(context.summary);
                for (AgentRepository.ChatMessage message : history) estimated += AgentClient.estimateTokens(message.content) + 8;
                if (estimated > requestSettings.tokenLimit * 2 && history.size() > 6) {
                    runOnUiThread(() -> { if (requestId == generationId) showStatus(getString(R.string.compressing_memory)); });
                    List<AgentRepository.ChatMessage> oldMessages = new ArrayList<>(history.subList(0, history.size() - 6));
                    String compressed = client.summarize(compressionModel, context.summary,
                            oldMessages, requestSettings.tokenLimit);
                    long summarizedId = oldMessages.get(oldMessages.size() - 1).id;
                    repository.updateConversationSummary(requestConversationId, compressed, summarizedId);
                    context = new AgentRepository.ConversationContext(compressed, summarizedId);
                    history = new ArrayList<>(history.subList(history.size() - 6, history.size()));
                }
                String answer = client.run(requestModel, context.summary, history, repository,
                        requestSmartSearch, requestSettings.tinyFishKey, attachments,
                        (fileId, offset, maxChars) -> readUploadedWord(
                                requestFiles, fileId, offset, maxChars), new AgentClient.Listener() {
                    @Override public void onStatus(String value) { runOnUiThread(() -> { if (requestId == generationId) showStatus(value); }); }
                    @Override public void onText(String delta) {
                        runOnUiThread(() -> {
                            if (requestId != generationId) return;
                            assistant.content += delta;
                            adapter.updateLast();
                            if (followStreaming) scrollToBottom();
                        });
                    }
                    @Override public void onToolStart(String callId, String name) {
                        runOnUiThread(() -> { if (requestId == generationId) showStatus(getString(R.string.agent_tool_running, readableTool(name))); });
                    }
                    @Override public void onToolEnd(String callId, String name, boolean success) {
                        runOnUiThread(() -> { if (requestId == generationId) showStatus(getString(success ? R.string.agent_tool_done
                                : R.string.agent_tool_failed, readableTool(name))); });
                    }
                    @Override public void onPasswordMatches(List<AgentRepository.CredentialReference> matches) {
                        runOnUiThread(() -> showPasswordMatches(matches));
                    }
                    @Override public void onUsage(int inputTokens, int outputTokens, int cachedTokens) {
                        usage[0] += inputTokens;
                        usage[1] += outputTokens;
                        usage[2] += cachedTokens;
                        runOnUiThread(() -> {
                            if (requestId != generationId) return;
                            assistant.setUsage(usage[0], usage[1], usage[2]);
                            adapter.updateLast();
                        });
                    }
                    @Override public boolean confirmFileOperation(String action, String path,
                                                                  String destination, String preview) {
                        return requestFileConfirmation(action, path, destination, preview);
                    }
                });
                repository.addMessage(requestConversationId, "assistant", answer, "complete", null, null,
                        usage[0], usage[1], usage[2]);
                runOnUiThread(() -> { if (requestId == generationId) finishGeneration(); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (requestId != generationId) return;
                    if (assistant.content.isEmpty()) adapter.removeLast();
                    finishGeneration();
                    if (e instanceof java.io.IOException && "Canceled".equalsIgnoreCase(e.getMessage())) {
                        Snackbar.make(findViewById(R.id.agent_root), R.string.agent_cancelled, Snackbar.LENGTH_SHORT).show();
                    } else {
                        showError(e);
                    }
                });
            }
        });
    }

    /**
     * 取消当前生成并清空界面会话。
     * 递增生成标识先使旧回调失效，再断开网络；新会话在下一条用户消息发送时才实际持久化。
     */
    private void startNewConversation() {
        generationId++;
        if (generating) client.cancel();
        conversationId = 0;
        adapter.clear();
        pendingFiles.clear();
        renderPendingFiles();
        empty.setVisibility(View.VISIBLE);
        finishGeneration();
    }

    private void showSelectedModel() {
        if (selectedModel == null) return;
        selectedModelName.setText(selectedModel.model);
        selectedModelSource.setText(selectedModel.groupName + "  ·  " + selectedModel.keyName);
        updateControlsSummary();
    }

    private void setControlsExpanded(boolean expanded) {
        controlsExpanded = expanded;
        agentControls.setVisibility(expanded ? View.VISIBLE : View.GONE);
        updateControlsSummary();
    }

    private void updateControlsSummary() {
        if (controlsToggle == null) return;
        if (controlsExpanded) {
            controlsToggle.setText(R.string.agent_controls_expanded);
        } else {
            String model = selectedModel == null ? getString(R.string.agent_select_model) : selectedModel.model;
            String search = smartSearchSwitch != null && smartSearchSwitch.isChecked()
                    ? getString(R.string.search_on) : getString(R.string.search_off);
            controlsToggle.setText(getString(R.string.agent_controls_collapsed, model, search));
        }
    }

    private AgentRepository.ModelOption findModel(long id, AgentRepository.ModelOption fallback) {
        for (AgentRepository.ModelOption model : models) if (model.id == id) return model;
        return fallback;
    }

    private void compressMemoryNow() {
        if (generating) {
            Snackbar.make(findViewById(R.id.agent_root), R.string.compressing_memory, Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (conversationId == 0 || selectedModel == null) {
            Snackbar.make(findViewById(R.id.agent_root), R.string.memory_too_short, Snackbar.LENGTH_SHORT).show();
            return;
        }
        final AgentRepository.AgentSettings currentSettings = settings == null
                ? new AgentRepository.AgentSettings(false, 0, 0, 0, 3200, "") : settings;
        final AgentRepository.ModelOption compressionModel = findModel(
                currentSettings.compressionModelId, selectedModel);
        final long targetConversationId = conversationId;
        showStatus(getString(R.string.compressing_memory));
        executor.execute(() -> {
            try {
                AgentRepository.ConversationContext context = repository.getConversationContext(targetConversationId);
                List<AgentRepository.ChatMessage> history = repository.getRecentMessages(targetConversationId, 100);
                List<AgentRepository.ChatMessage> pending = new ArrayList<>();
                for (AgentRepository.ChatMessage message : history) {
                    if (message.id > context.summarizedMessageId) pending.add(message);
                }
                if (pending.size() <= 6) {
                    runOnUiThread(() -> {
                        status.setVisibility(View.GONE);
                        Snackbar.make(findViewById(R.id.agent_root), R.string.memory_too_short, Snackbar.LENGTH_SHORT).show();
                    });
                    return;
                }
                List<AgentRepository.ChatMessage> oldMessages = new ArrayList<>(pending.subList(0, pending.size() - 6));
                String compressed = client.summarize(compressionModel, context.summary,
                        oldMessages, currentSettings.tokenLimit);
                repository.updateConversationSummary(targetConversationId, compressed,
                        oldMessages.get(oldMessages.size() - 1).id);
                runOnUiThread(() -> {
                    status.setVisibility(View.GONE);
                    Snackbar.make(findViewById(R.id.agent_root), R.string.memory_compressed, Snackbar.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    status.setVisibility(View.GONE);
                    showError(error);
                });
            }
        });
    }

    /**
     * 展示模型工具检索到的密码条目元数据。
     * 对话框不会自动读取秘密，只有用户明确选择某条记录才进入本地明文读取流程。
     */
    private void showPasswordMatches(List<AgentRepository.CredentialReference> matches) {
        if (matches.isEmpty()) return;
        String[] labels = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            AgentRepository.CredentialReference item = matches.get(i);
            labels[i] = item.name + "\n" + (item.category == null ? "其他" : item.category);
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.password_matches)
                .setMessage(R.string.password_local_only)
                .setItems(labels, (dialog, which) -> revealPassword(matches.get(which)))
                .setNegativeButton(R.string.cancel, null).show();
    }

    /**
     * 在独立后台线程按条目标识读取密码明文，然后仅交给本地对话框。
     * 该值不经过 AgentClient，不加入聊天记录，也不作为工具结果发送到远端服务。
     */
    private void revealPassword(AgentRepository.CredentialReference reference) {
        localExecutor.execute(() -> {
            try {
                Password password = passwordService.selectPassword(reference.id);
                runOnUiThread(() -> showLocalSecret(password.getName(), password.getPassword()));
            } catch (RuntimeException e) {
                runOnUiThread(() -> showError(e));
            }
        });
    }

    /**
     * 以默认遮罩方式显示本机秘密，并提供显隐切换和显式复制入口。
     * 显示操作不改变持久化数据；复制会把明文交给系统剪贴板，因此其后续可见性受系统剪贴板策略约束。
     */
    private void showLocalSecret(String title, String secret) {
        TextView value = new TextView(this);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        value.setPadding(padding, padding / 2, padding, padding / 2);
        value.setText(R.string.password_mask);
        value.setTextIsSelectable(true);
        value.setTextSize(18);
        value.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface));
        final boolean[] shown = {false};
        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title).setMessage(R.string.password_local_only).setView(value)
                .setNeutralButton(R.string.show, null)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.copy, (popup, which) -> copySecret(secret, R.string.copied))
                .create();
        dialog.setOnShowListener(unused -> dialog
                        .getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                            shown[0] = !shown[0];
                            value.setText(shown[0] ? secret : getString(R.string.password_mask));
                            ((android.widget.Button) view).setText(shown[0] ? R.string.hide : R.string.show);
                        }));
        dialog.show();
    }

    private void copySecret(String value, int feedback) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.password), value));
        Snackbar.make(findViewById(R.id.agent_root), feedback, Snackbar.LENGTH_SHORT).show();
    }

    private boolean requestFileConfirmation(String action, String path, String destination,
                                            String preview) {
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] allowed = {false};
        runOnUiThread(() -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.file_operation_confirm)
                .setMessage(getString(R.string.file_operation_details, action, path,
                        destination == null ? "" : destination, preview == null ? "" : preview))
                .setNegativeButton(R.string.cancel, (dialog, which) -> latch.countDown())
                .setPositiveButton(R.string.allow_once, (dialog, which) -> {
                    allowed[0] = true;
                    latch.countDown();
                })
                .setOnCancelListener(dialog -> latch.countDown())
                .show());
        try {
            if (!latch.await(2, TimeUnit.MINUTES)) return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
        return allowed[0];
    }

    private int resolveThemeColor(int attribute) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }

    private void showModelPicker() {
        if (models.isEmpty()) {
            startActivity(new Intent(this, ApiKeyActivity.class));
            return;
        }
        BaseAdapter listAdapter = new BaseAdapter() {
            @Override public int getCount() { return models.size(); }
            @Override public Object getItem(int position) { return models.get(position); }
            @Override public long getItemId(int position) { return models.get(position).id; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView == null ? getLayoutInflater().inflate(R.layout.item_model_option, parent, false) : convertView;
                AgentRepository.ModelOption option = models.get(position);
                view.<TextView>findViewById(R.id.model_option_name).setText(option.model);
                view.<TextView>findViewById(R.id.model_option_source).setText(option.groupName + "  ·  " + option.keyName);
                return view;
            }
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.model_list_title)
                .setAdapter(listAdapter, (dialog, which) -> {
                    AgentRepository.ModelOption next = models.get(which);
                    if (selectedModel == null || selectedModel.id != next.id) {
                        selectedModel = next;
                        showSelectedModel();
                        startNewConversation();
                    }
                }).show();
    }

    /** 在后台读取最近更新的持久化会话，并切回 UI 线程恢复其模型和消息。 */
    private void restoreLatestConversation() {
        executor.execute(() -> {
            List<AgentRepository.Conversation> conversations = repository.getConversations();
            if (!conversations.isEmpty()) runOnUiThread(() -> openConversation(conversations.get(0)));
        });
    }

    private void showHistory() {
        executor.execute(() -> {
            List<AgentRepository.Conversation> conversations = repository.getConversations();
            runOnUiThread(() -> {
                if (conversations.isEmpty()) {
                    Snackbar.make(findViewById(R.id.agent_root), R.string.no_conversations, Snackbar.LENGTH_SHORT).show();
                    return;
                }
                String[] labels = new String[conversations.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = conversations.get(i).title
                        + (conversations.get(i).modelName == null ? "" : "\n" + conversations.get(i).modelName);
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.conversation_history)
                        .setItems(labels, (dialog, which) -> openConversation(conversations.get(which)))
                        .setNegativeButton(R.string.cancel, null).show();
            });
        });
    }

    /**
     * 切换到一个持久化会话。
     * 当前生成会先取消且旧回调失效，随后后台读取完整消息；仅用户和助手角色进入可见聊天列表。
     */
    private void openConversation(AgentRepository.Conversation conversation) {
        if (generating) client.cancel();
        generationId++;
        followStreaming = true;
        conversationId = conversation.id;
        for (AgentRepository.ModelOption option : models) {
            if (option.id == conversation.modelId) selectedModel = option;
        }
        showSelectedModel();
        executor.execute(() -> {
            List<AgentRepository.ChatMessage> messages = repository.getRecentMessages(conversation.id, 100);
            runOnUiThread(() -> {
                adapter.clear();
                for (AgentRepository.ChatMessage message : messages) {
                    if ("user".equals(message.role) || "assistant".equals(message.role))
                        adapter.add(new UiMessage(message.role, message.content,
                                message.inputTokens, message.outputTokens, message.cachedTokens));
                }
                empty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
                finishGeneration();
                scrollToBottom();
            });
        });
    }

    private void finishGeneration() {
        generating = false;
        send.setImageResource(R.drawable.ic_send);
        status.setVisibility(View.GONE);
    }

    private void showStatus(String value) {
        status.setText(value);
        status.setVisibility(View.VISIBLE);
    }

    private void showError(Exception exception) {
        String message = exception.getMessage();
        Snackbar.make(findViewById(R.id.agent_root),
                message == null || message.trim().isEmpty() ? getString(R.string.operation_failed) : message,
                Snackbar.LENGTH_LONG).show();
    }

    private void scrollToBottom() {
        if (adapter.getItemCount() > 0) messageList.scrollToPosition(adapter.getItemCount() - 1);
    }

    private static String readableTool(String name) {
        switch (name) {
            case "get_current_time": return "当前时间";
            case "get_device_info": return "设备信息";
            case "list_toolbox_modules": return "工具箱模块";
            case "search_passwords": return "密码条目检索";
            case "query_notes": return "笔记检索";
            case "network_search": return "网络搜索";
            case "workspace_shell": return "文件工作区";
            case "read_word_file": return "Word 文件解析";
            default: return name;
        }
    }

    private void onWordFilesSelected(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        for (Uri uri : uris) {
            if (pendingFiles.size() >= 5) {
                Snackbar.make(findViewById(R.id.agent_root), R.string.too_many_uploads,
                        Snackbar.LENGTH_LONG).show();
                break;
            }
            String name = displayName(uri);
            if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")) {
                Snackbar.make(findViewById(R.id.agent_root), R.string.word_files_only,
                        Snackbar.LENGTH_LONG).show();
                continue;
            }
            boolean duplicate = false;
            for (UploadedWord file : pendingFiles) {
                if (file.uri.equals(uri)) duplicate = true;
            }
            if (!duplicate) pendingFiles.add(new UploadedWord(UUID.randomUUID().toString(), name,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", uri));
        }
        renderPendingFiles();
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (RuntimeException ignored) { }
        String fallback = uri.getLastPathSegment();
        return fallback == null || fallback.trim().isEmpty() ? "document.docx" : fallback;
    }

    private void renderPendingFiles() {
        if (attachmentList == null) return;
        attachmentList.removeAllViews();
        for (UploadedWord file : new ArrayList<>(pendingFiles)) {
            MaterialButton chip = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            chip.setText(file.name + "  ×");
            chip.setAllCaps(false);
            chip.setOnClickListener(view -> {
                pendingFiles.remove(file);
                renderPendingFiles();
            });
            attachmentList.addView(chip);
        }
        attachmentStrip.setVisibility(pendingFiles.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private AgentClient.FileChunk readUploadedWord(List<UploadedWord> files, String fileId,
                                                    int offset, int maxChars) throws Exception {
        UploadedWord match = null;
        for (UploadedWord file : files) if (file.id.equals(fileId)) match = file;
        if (match == null) throw new IllegalArgumentException("文件 ID 不存在");
        if (match.text == null) match.text = new WordParse(this).parseFromUri(match.uri);
        int start = Math.max(0, Math.min(offset, match.text.length()));
        int end = Math.min(match.text.length(), start + Math.max(100, Math.min(maxChars, 20000)));
        return new AgentClient.FileChunk(match.id, match.name, match.text.substring(start, end),
                start, match.text.length());
    }

    @Override
    /** 页面销毁时断开活动请求并停止执行器，避免后台任务继续持有界面实例。 */
    protected void onDestroy() {
        client.cancel();
        executor.shutdownNow();
        localExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (swipeBack != null && swipeBack.handle(event)) return true;
        return super.dispatchTouchEvent(event);
    }
}

final class UploadedWord {
    final String id;
    final String name;
    final String mimeType;
    final Uri uri;
    String text;

    UploadedWord(String id, String name, String mimeType, Uri uri) {
        this.id = id;
        this.name = name;
        this.mimeType = mimeType;
        this.uri = uri;
    }
}

final class UiMessage {
    final String role;
    String content;
    int inputTokens, outputTokens, cachedTokens;
    UiMessage(String role, String content) { this(role, content, 0, 0, 0); }
    UiMessage(String role, String content, int inputTokens, int outputTokens, int cachedTokens) {
        this.role = role; this.content = content;
        this.inputTokens=inputTokens; this.outputTokens=outputTokens; this.cachedTokens=cachedTokens;
    }
    void setUsage(int inputTokens, int outputTokens, int cachedTokens) {
        this.inputTokens=inputTokens; this.outputTokens=outputTokens; this.cachedTokens=cachedTokens;
    }
}

final class AgentMessageAdapter extends RecyclerView.Adapter<AgentMessageAdapter.Holder> {
    private final List<UiMessage> items = new ArrayList<>();
    void add(UiMessage message) { items.add(message); notifyItemInserted(items.size() - 1); }
    void updateLast() { if (!items.isEmpty()) notifyItemChanged(items.size() - 1); }
    void removeLast() { if (!items.isEmpty()) { int i=items.size()-1; items.remove(i); notifyItemRemoved(i); } }
    void clear() { items.clear(); notifyDataSetChanged(); }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_agent_message, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        UiMessage item = items.get(position);
        boolean user = "user".equals(item.role);
        holder.role.setText(user ? "YOU" : "AGENT");
        holder.content.setText(item.content.isEmpty() ? "…"
                : user ? item.content : MarkdownRenderer.render(item.content));
        holder.content.setMovementMethod(user ? null : LinkMovementMethod.getInstance());
        boolean hasUsage = !user && (item.inputTokens > 0 || item.outputTokens > 0 || item.cachedTokens > 0);
        holder.usage.setVisibility(hasUsage ? View.VISIBLE : View.GONE);
        if (hasUsage) holder.usage.setText(holder.itemView.getContext().getString(
                R.string.message_usage, item.inputTokens, item.outputTokens, item.cachedTokens));
        holder.copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) view.getContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("message", item.content));
            Snackbar.make(view, R.string.message_copied, Snackbar.LENGTH_SHORT).show();
        });
        holder.row.setGravity(user ? Gravity.END : Gravity.START);
        holder.card.setCardBackgroundColor(holder.itemView.getContext().getColor(
                user ? R.color.toolbox_mint : R.color.toolbox_surface));
    }
    @Override public int getItemCount() { return items.size(); }
    static final class Holder extends RecyclerView.ViewHolder {
        final LinearLayout row; final TextView role, content, usage; final View copy; final MaterialCardView card;
        Holder(@NonNull View itemView) { super(itemView); row=(LinearLayout)itemView; role=itemView.findViewById(R.id.message_role); content=itemView.findViewById(R.id.message_content); usage=itemView.findViewById(R.id.message_usage); copy=itemView.findViewById(R.id.message_copy); card=itemView.findViewById(R.id.message_card); }
    }
}
