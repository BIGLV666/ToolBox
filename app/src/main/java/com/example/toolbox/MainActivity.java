package com.example.toolbox;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.toolbox.Password.model.Password;
import com.example.toolbox.Password.service.PasswordService;
import com.example.toolbox.agent.ui.AgentActivity;
import com.example.toolbox.util.DeviceLocationProvider;
import com.example.toolbox.util.WeatherService;
import com.example.toolbox.util.SwipeBackHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用首页，同时承载密码工具的列表页。
 *
 * <p>首页、密码页并不是两个 Activity，而是同一布局中互斥显示的两个页面。密码工具卡片、系统返回键和
 * 边缘侧滑都只切换这两个 View；智能助手与工具库则通过 Intent 进入独立 Activity。</p>
 *
 * <p>天气请求与密码数据库操作共用一个单线程执行器，避免网络或磁盘访问阻塞主线程；所有 View 更新均
 * 通过 {@link #runOnUiThread(Runnable)} 切回主线程。执行器的串行特性也避免密码增删改查相互穿插。</p>
 */
public class MainActivity extends AppCompatActivity implements PasswordAdapter.Listener {
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final List<Password> allPasswords = new ArrayList<>();
    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (DeviceLocationProvider.hasPermission(this)) {
                    requestWeatherForCurrentLocation();
                } else {
                    showWeatherStatus(R.string.weather_location_permission);
                }
            });

    private View homePage;
    private View passwordPage;
    private View emptyState;
    private View loading;
    private TextView emptyTitle;
    private TextView emptyDescription;
    private TextInputEditText searchInput;
    private PasswordAdapter adapter;
    private PasswordService passwordService;
    private String selectedCategory = "全部";
    private SwipeBackHelper.Controller passwordSwipeBack;
    private DeviceLocationProvider locationProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        bindViews();
        configureInsets();
        configureNavigation();
        configurePasswordList();
        PDFBoxResourceLoader.init(getApplicationContext());
        passwordSwipeBack = SwipeBackHelper.create(passwordPage, this::showHome);
        ((TextView) findViewById(R.id.daily_quote)).setText(WeatherService.dailyQuote());
        findViewById(R.id.weather_scene).setOnClickListener(view -> loadWeather());
        loadWeather();
        if (getIntent().getBooleanExtra("open_password", false)) openPasswordModule();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (passwordPage.getVisibility() == View.VISIBLE) {
                    showHome();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void bindViews() {
        homePage = findViewById(R.id.home_page);
        passwordPage = findViewById(R.id.password_page);
        emptyState = findViewById(R.id.empty_state);
        loading = findViewById(R.id.loading);
        emptyTitle = findViewById(R.id.empty_title);
        emptyDescription = findViewById(R.id.empty_description);
        searchInput = findViewById(R.id.search_input);
    }

    private void configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private void loadWeather() {
        if (!DeviceLocationProvider.hasPermission(this)) {
            showWeatherStatus(R.string.weather_requesting_location);
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            });
            return;
        }
        requestWeatherForCurrentLocation();
    }

    private void requestWeatherForCurrentLocation() {
        showWeatherStatus(R.string.weather_locating);
        if (locationProvider == null) locationProvider = new DeviceLocationProvider(this);
        locationProvider.requestCurrentLocation(location -> {
            if (location == null) {
                showWeatherStatus(R.string.weather_location_unavailable);
                return;
            }
            loadWeather(location);
        });
    }

    private void loadWeather(Location location) {
        // WeatherService only reuses cached weather when its coordinates are still close to this fix.
        databaseExecutor.execute(() -> {
            try {
                WeatherService.WeatherData weather = new WeatherService(this).load(location);
                runOnUiThread(() -> renderWeather(weather));
            } catch (Exception ignored) {
                runOnUiThread(() -> showWeatherStatus(R.string.weather_unavailable));
            }
        });
    }

    private void showWeatherStatus(int message) {
        ((TextView) findViewById(R.id.weather_condition)).setText(message);
    }

    private void renderWeather(WeatherService.WeatherData weather) {
        ((TextView) findViewById(R.id.weather_location)).setText(weather.city);
        ((TextView) findViewById(R.id.weather_condition)).setText(weather.condition);
        ((TextView) findViewById(R.id.weather_temperature)).setText(Math.round(weather.temperature) + "°");
        String rain = weather.rainProbability < 0 ? "" : "  ·  降水 " + weather.rainProbability + "%";
        ((TextView) findViewById(R.id.weather_details)).setText("体感 " + Math.round(weather.apparent)
                + "°  ·  " + Math.round(weather.low) + "° / " + Math.round(weather.high)
                + "°  ·  湿度 " + weather.humidity + "%" + rain);
        int background = WeatherService.scene(weather.code) == 2 ? R.drawable.bg_weather_rain
                : WeatherService.scene(weather.code) == 1 ? R.drawable.bg_weather_cloud
                : R.drawable.bg_weather_clear;
        findViewById(R.id.weather_scene).setBackgroundResource(background);
    }

    private void configureNavigation() {
        // 密码工具留在当前 Activity 内切页，其余一级工具使用显式 Intent 建立独立页面返回栈。
        findViewById(R.id.password_tool_card).setOnClickListener(view -> openPasswordModule());
        findViewById(R.id.agent_tool_card).setOnClickListener(view ->
                startActivity(new Intent(this, AgentActivity.class)));
        findViewById(R.id.feature_library_card).setOnClickListener(view ->
                startActivity(new Intent(this, ToolsActivity.class)));
        findViewById(R.id.password_toolbar).setOnClickListener(view -> showHome());
        ((com.google.android.material.appbar.MaterialToolbar) findViewById(R.id.password_toolbar))
                .setNavigationOnClickListener(view -> showHome());
        findViewById(R.id.add_password).setOnClickListener(view -> showPasswordForm(null));
    }

    private void configurePasswordList() {
        adapter = new PasswordAdapter(this);
        RecyclerView list = findViewById(R.id.password_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterPasswords();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        com.google.android.material.chip.ChipGroup categories = findViewById(R.id.category_group);
        categories.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            TextView selected = findViewById(checkedIds.get(0));
            selectedCategory = selected.getText().toString();
            filterPasswords();
        });
    }

    private void openPasswordModule() {
        homePage.setVisibility(View.GONE);
        passwordPage.setVisibility(View.VISIBLE);
        if (passwordService == null) {
            try {
                passwordService = new PasswordService(this);
            } catch (RuntimeException exception) {
                showError(exception);
                return;
            }
        }
        loadPasswords();
    }

    private void showHome() {
        passwordPage.setVisibility(View.GONE);
        homePage.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (passwordPage != null && passwordPage.getVisibility() == View.VISIBLE
                && passwordSwipeBack != null && passwordSwipeBack.handle(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 工具库以 SINGLE_TOP/CLEAR_TOP 返回已有首页时，由新 Intent 直接打开密码内页。
        if (intent.getBooleanExtra("open_password", false)) openPasswordModule();
    }

    private void loadPasswords() {
        loading.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        databaseExecutor.execute(() -> {
            try {
                List<Password> loaded = passwordService.getPasswords();
                runOnUiThread(() -> {
                    allPasswords.clear();
                    allPasswords.addAll(loaded);
                    loading.setVisibility(View.GONE);
                    filterPasswords();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> {
                    loading.setVisibility(View.GONE);
                    showError(exception);
                });
            }
        });
    }

    private void filterPasswords() {
        String query = textOf(searchInput).toLowerCase(Locale.ROOT);
        List<Password> filtered = new ArrayList<>();
        for (Password password : allPasswords) {
            String name = safe(password.getName()).toLowerCase(Locale.ROOT);
            String notes = safe(password.getNotes()).toLowerCase(Locale.ROOT);
            boolean queryMatches = query.isEmpty() || name.contains(query) || notes.contains(query);
            boolean categoryMatches = "全部".equals(selectedCategory)
                    || selectedCategory.equals(password.getCategory());
            if (queryMatches && categoryMatches) {
                filtered.add(password);
            }
        }
        adapter.submitList(filtered);
        boolean isEmpty = filtered.isEmpty();
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (isEmpty) {
            boolean hasFilter = !query.isEmpty() || !"全部".equals(selectedCategory);
            emptyTitle.setText(hasFilter ? R.string.empty_search_title : R.string.empty_title);
            emptyDescription.setText(hasFilter ? R.string.empty_search_desc : R.string.empty_desc);
        }
    }

    private void showPasswordForm(Password existing) {
        View form = getLayoutInflater().inflate(R.layout.dialog_password, null, false);
        TextInputLayout nameLayout = form.findViewById(R.id.name_layout);
        TextInputLayout passwordLayout = form.findViewById(R.id.password_layout);
        TextInputEditText name = form.findViewById(R.id.name_input);
        TextInputEditText password = form.findViewById(R.id.password_input);
        TextInputEditText url = form.findViewById(R.id.url_input);
        TextInputEditText notes = form.findViewById(R.id.notes_input);
        AutoCompleteTextView category = form.findViewById(R.id.category_input);
        String[] categoryValues = getResources().getStringArray(R.array.password_categories);
        category.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, categoryValues));
        category.setText(existing == null ? getString(R.string.category_other) : existing.getCategory(), false);

        if (existing != null) {
            name.setText(existing.getName());
            password.setText(existing.getPassword());
            url.setText(existing.getUrl());
            notes.setText(existing.getNotes());
        }

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.add_password : R.string.edit_password)
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(unused -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    nameLayout.setError(null);
                    passwordLayout.setError(null);
                    String nameValue = textOf(name);
                    String passwordValue = textOf(password);
                    if (nameValue.isEmpty()) {
                        nameLayout.setError(getString(R.string.required_name));
                        return;
                    }
                    if (passwordValue.isEmpty()) {
                        passwordLayout.setError(getString(R.string.required_password));
                        return;
                    }
                    Password value = existing == null ? new Password() : existing;
                    value.setName(nameValue);
                    value.setPassword(passwordValue);
                    value.setCategory(category.getText().toString().trim());
                    value.setUrl(textOf(url));
                    value.setNotes(textOf(notes));
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    savePassword(value, existing == null, dialog);
                }));
        dialog.show();
    }

    private void savePassword(Password password, boolean isNew, androidx.appcompat.app.AlertDialog dialog) {
        databaseExecutor.execute(() -> {
            try {
                if (isNew) {
                    passwordService.insertPassword(password);
                } else {
                    passwordService.updatePassword(password);
                }
                runOnUiThread(() -> {
                    dialog.dismiss();
                    Snackbar.make(findViewById(R.id.main), isNew ? R.string.saved : R.string.updated,
                            Snackbar.LENGTH_SHORT).show();
                    loadPasswords();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    showError(exception);
                });
            }
        });
    }

    @Override
    public void onOpen(Password password) {
        View detail = getLayoutInflater().inflate(R.layout.dialog_password_detail, null, false);
        TextView passwordText = detail.findViewById(R.id.detail_password);
        TextView toggle = detail.findViewById(R.id.detail_toggle);
        detail.<TextView>findViewById(R.id.detail_category).setText(password.getCategory());
        detail.<TextView>findViewById(R.id.detail_url).setText(
                safe(password.getUrl()).isEmpty() ? getString(R.string.no_url) : password.getUrl());
        detail.<TextView>findViewById(R.id.detail_notes).setText(
                safe(password.getNotes()).isEmpty() ? getString(R.string.no_notes) : password.getNotes());
        toggle.setOnClickListener(view -> togglePassword(passwordText, toggle, password.getPassword()));
        detail.findViewById(R.id.detail_copy).setOnClickListener(view -> copyPassword(password.getPassword()));

        new MaterialAlertDialogBuilder(this)
                .setTitle(password.getName())
                .setView(detail)
                .setNeutralButton(R.string.delete, (dialog, which) -> confirmDelete(password))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.edit, (dialog, which) -> showPasswordForm(password))
                .show();
    }

    @Override
    public void onCopy(Password password) {
        copyPassword(password.getPassword());
    }

    private void copyPassword(String password) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.password), password));
        Snackbar.make(findViewById(R.id.main), R.string.copied, Snackbar.LENGTH_SHORT).show();
    }

    private void confirmDelete(Password password) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> deletePassword(password))
                .show();
    }

    private void deletePassword(Password password) {
        databaseExecutor.execute(() -> {
            try {
                passwordService.deletePassword(password.getPasswordId());
                runOnUiThread(() -> {
                    Snackbar.make(findViewById(R.id.main), R.string.deleted, Snackbar.LENGTH_SHORT).show();
                    loadPasswords();
                });
            } catch (RuntimeException exception) {
                runOnUiThread(() -> showError(exception));
            }
        });
    }

    private void showError(RuntimeException exception) {
        String message = exception.getMessage();
        Snackbar.make(findViewById(R.id.main),
                message == null || message.trim().isEmpty() ? getString(R.string.operation_failed) : message,
                Snackbar.LENGTH_LONG).show();
    }

    private static String textOf(TextView view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static void togglePassword(TextView passwordView, TextView button, String password) {
        boolean hidden = passwordView.getTag() == null || !(Boolean) passwordView.getTag();
        passwordView.setText(hidden ? password : passwordView.getContext().getString(R.string.password_mask));
        button.setText(hidden ? R.string.hide : R.string.show);
        passwordView.setTag(hidden);
    }

    @Override
    protected void onDestroy() {
        if (locationProvider != null) locationProvider.cancel();
        databaseExecutor.shutdown();
        super.onDestroy();
    }
}

final class PasswordAdapter extends RecyclerView.Adapter<PasswordAdapter.Holder> {
    interface Listener {
        void onOpen(Password password);
        void onCopy(Password password);
    }

    private final List<Password> items = new ArrayList<>();
    private final Listener listener;

    PasswordAdapter(Listener listener) {
        this.listener = listener;
    }

    void submitList(List<Password> passwords) {
        items.clear();
        items.addAll(passwords);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_password, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Password password = items.get(position);
        holder.name.setText(password.getName());
        String name = password.getName() == null ? "P" : password.getName().trim();
        holder.mark.setText(name.isEmpty() ? "P" : name.substring(0, 1).toUpperCase(Locale.ROOT));
        holder.meta.setText(password.getNotes() == null || password.getNotes().trim().isEmpty()
                ? holder.itemView.getContext().getString(R.string.no_notes) : password.getNotes());
        holder.category.setText(password.getCategory());
        holder.password.setText(R.string.password_mask);
        holder.password.setTag(false);
        holder.toggle.setText(R.string.show);
        holder.toggle.setOnClickListener(view -> MainActivity.togglePassword(
                holder.password, holder.toggle, password.getPassword()));
        holder.copy.setOnClickListener(view -> listener.onCopy(password));
        holder.itemView.setOnClickListener(view -> listener.onOpen(password));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView mark;
        final TextView name;
        final TextView meta;
        final TextView category;
        final TextView password;
        final TextView toggle;
        final View copy;

        Holder(@NonNull View itemView) {
            super(itemView);
            mark = itemView.findViewById(R.id.item_mark);
            name = itemView.findViewById(R.id.item_name);
            meta = itemView.findViewById(R.id.item_meta);
            category = itemView.findViewById(R.id.item_category);
            password = itemView.findViewById(R.id.item_password);
            toggle = itemView.findViewById(R.id.item_toggle);
            copy = itemView.findViewById(R.id.item_copy);
        }
    }
}
