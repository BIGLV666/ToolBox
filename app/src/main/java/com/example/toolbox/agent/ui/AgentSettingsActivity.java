package com.example.toolbox.agent.ui;

import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.example.toolbox.R;
import com.example.toolbox.agent.repository.AgentRepository;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Agent 的统一设置页：默认模型、智能搜索、记忆压缩档位和 TinyFish 凭据。 */
public final class AgentSettingsActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<AgentRepository.ModelOption> models = new ArrayList<>();
    private AgentRepository repository;
    private MaterialSwitch smartSearch;
    private AutoCompleteTextView chatModel, searchModel, compressionModel, memorySize;
    private TextInputEditText tinyFishKey;
    private android.widget.TextView workspaceStatus;
    private android.widget.TextView allFilesStatus;
    private String workspaceTreeUri = "";
    private final ActivityResultLauncher<Uri> workspacePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onWorkspaceSelected);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_agent_settings);
        repository = new AgentRepository(this);
        smartSearch = findViewById(R.id.settings_smart_search);
        chatModel = findViewById(R.id.default_chat_model);
        searchModel = findViewById(R.id.default_search_model);
        compressionModel = findViewById(R.id.default_compression_model);
        memorySize = findViewById(R.id.memory_size_input);
        tinyFishKey = findViewById(R.id.tinyfish_key_input);
        workspaceStatus = findViewById(R.id.workspace_status);
        allFilesStatus = findViewById(R.id.all_files_status);
        ((com.google.android.material.appbar.MaterialToolbar)findViewById(R.id.settings_toolbar))
                .setNavigationOnClickListener(view -> finish());
        findViewById(R.id.manage_settings_keys).setOnClickListener(view ->
                startActivity(new Intent(this, ApiKeyActivity.class)));
        findViewById(R.id.choose_workspace).setOnClickListener(view -> workspacePicker.launch(null));
        findViewById(R.id.grant_all_files).setOnClickListener(view -> requestAllFilesAccess());
        findViewById(R.id.save_agent_settings).setOnClickListener(view -> save());
        memorySize.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line,
                getResources().getStringArray(R.array.memory_sizes)));
    }

    @Override protected void onResume() {
        super.onResume();
        updateAllFilesStatus();
        executor.execute(() -> {
            List<AgentRepository.ModelOption> loaded = repository.getModelOptions();
            AgentRepository.AgentSettings settings = repository.getSettings();
            runOnUiThread(() -> bind(loaded, settings));
        });
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
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2101);
        }
    }

    private void updateAllFilesStatus() {
        boolean granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Environment.isExternalStorageManager()
                : androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        allFilesStatus.setText(granted ? R.string.all_files_granted : R.string.all_files_not_granted);
    }

    private void bind(List<AgentRepository.ModelOption> loaded, AgentRepository.AgentSettings settings) {
        models.clear();
        models.addAll(loaded);
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.follow_chat_model));
        for (AgentRepository.ModelOption model : models) labels.add(model.toString());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, labels);
        chatModel.setAdapter(adapter);
        searchModel.setAdapter(adapter);
        compressionModel.setAdapter(adapter);
        chatModel.setText(labelFor(settings.chatModelId, false), false);
        searchModel.setText(labelFor(settings.searchModelId, true), false);
        compressionModel.setText(labelFor(settings.compressionModelId, true), false);
        smartSearch.setChecked(settings.smartSearch);
        memorySize.setText(settings.tokenLimit == 2000 ? getString(R.string.memory_2000)
                : settings.tokenLimit == 4000 ? getString(R.string.memory_4000)
                : getString(R.string.memory_3200), false);
        tinyFishKey.setText(settings.tinyFishKey);
        workspaceTreeUri = settings.workspaceTreeUri;
        workspaceStatus.setText(workspaceTreeUri.isEmpty()
                ? getString(R.string.workspace_not_selected) : Uri.parse(workspaceTreeUri).getLastPathSegment());
    }

    private void save() {
        long chatId = idFor(chatModel.getText().toString());
        long searchId = idFor(searchModel.getText().toString());
        long compressionId = idFor(compressionModel.getText().toString());
        int limit = memorySize.getText().toString().startsWith("2000") ? 2000
                : memorySize.getText().toString().startsWith("4000") ? 4000 : 3200;
        String key = tinyFishKey.getText() == null ? "" : tinyFishKey.getText().toString();
        boolean searchEnabled = smartSearch.isChecked();
        executor.execute(() -> {
            repository.saveSettings(searchEnabled, chatId, searchId, compressionId, limit, key,
                    workspaceTreeUri);
            runOnUiThread(() -> {
                Snackbar.make(findViewById(R.id.settings_root), R.string.settings_saved, Snackbar.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    private void onWorkspaceSelected(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException error) {
            Snackbar.make(findViewById(R.id.settings_root), error.getMessage(), Snackbar.LENGTH_LONG).show();
            return;
        }
        workspaceTreeUri = uri.toString();
        workspaceStatus.setText(uri.getLastPathSegment());
    }

    private String labelFor(long id, boolean allowFollow) {
        for (AgentRepository.ModelOption model : models) if (model.id == id) return model.toString();
        return allowFollow || models.isEmpty() ? getString(R.string.follow_chat_model) : models.get(0).toString();
    }

    private long idFor(String label) {
        for (AgentRepository.ModelOption model : models) if (model.toString().equals(label)) return model.id;
        return 0;
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
