package com.example.toolbox.note;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.DragEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.toolbox.R;
import com.example.toolbox.cloud.CloudClient;
import com.example.toolbox.cloud.CloudConfig;
import com.example.toolbox.cloud.CloudRepository;
import com.example.toolbox.cloud.Keyring;
import com.example.toolbox.cloud.NoteCipher;
import com.example.toolbox.cloud.NoteSyncClient;
import com.example.toolbox.cloud.ObjectKeys;
import com.example.toolbox.cloud.QiniuManagementClient;
import com.example.toolbox.note.ui.NoteCloudSettingsActivity;
import com.example.toolbox.util.SwipeBackHelper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * 笔记模块的列表、文件夹、编辑器和阅读器页面。
 *
 * <p>数据库方法均为同步调用，因此统一提交给单线程 {@code executor}；查询、保存、移动和删除按提交顺序
 * 串行执行，View 读取和更新则切回主线程。搜索输入在主线程延迟 180ms 发起查询，并以递增版本号丢弃
 * 已过时请求的 UI 结果，避免较早关键词的结果覆盖最新关键词。</p>
 *
 * <p>列表页和详情页是同一 Activity 内的 View 状态。详情又分编辑态与阅读态：保存成功后重新读取完整笔记
 * 并进入阅读态；已有笔记编辑时返回会放弃尚未保存到 {@code current} 的输入并恢复阅读态，新建但未保存的
 * 笔记返回则直接回列表。</p>
 */
public final class NoteActivity extends AppCompatActivity implements NoteAdapter.Listener {
    private static final String TAG = "NoteActivity";
    /** 删除笔记后，云端对象保留的天数：留出误删缓冲，到期由服务端自动清理。 */
    private static final int TRASH_EXPIRE_DAYS = 7;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<NoteRepository.Note> notes = new ArrayList<>();
    private final List<NoteRepository.Folder> folders = new ArrayList<>();
    /** 待询问"是否删除本地文件"的附件，串行展示避免弹窗叠弹窗。 */
    private final Deque<NoteRepository.Attachment> deleteAsks = new ArrayDeque<>();
    private boolean deleteAskShowing;
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new OpenDocumentWithWrite(), this::onFilePicked);
    private NoteRepository repository;
    private CloudRepository cloudRepository;
    /** 云同步配置。只在后台执行器里读写，避免在主线程访问数据库。 */
    private volatile CloudConfig cloudConfig;
    /** 当前进行中的云端请求，页面销毁时用来中断连接。 */
    private volatile CloudClient cloudClient;
    /** 已解锁的加密凭据。为空表示未解锁，此时附件与正文都不会上传。 */
    private volatile Keyring keyring;
    private NoteAdapter adapter;
    private View listPage, detailPage, editor, reader, empty;
    private EditText search, titleInput, bodyInput, remarkInput;
    private LinearLayout editorAttachments, readerAttachments;
    private MaterialToolbar detailToolbar;
    private MaterialToolbar listToolbar;
    private TextView location, rootDropTarget;
    private NoteRepository.Note current;
    private NoteRepository.Folder currentFolder;
    private SwipeBackHelper.Controller swipeBack;
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private int searchVersion;
    private final Runnable searchTask = this::loadNotes;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_notes);
        repository = new NoteRepository(this);
        cloudRepository = new CloudRepository(this);
        bindViews();
        configureInsets();
        configureActions();
        loadNotes();
        clearStaleCache();
        purgeOrphanFiles();
        // 启动时静默同步一次：拉取别人的修改与删除，推送自己没传完的。失败不打扰，结果记在日志里。
        syncNow(false);
        swipeBack = SwipeBackHelper.create(findViewById(R.id.note_root), this::handleBack);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
    }

    private void bindViews() {
        listPage = findViewById(R.id.note_list_page);
        detailPage = findViewById(R.id.note_detail_page);
        editor = findViewById(R.id.note_editor);
        reader = findViewById(R.id.note_reader);
        empty = findViewById(R.id.note_empty);
        search = findViewById(R.id.note_search_input);
        titleInput = findViewById(R.id.note_title_input);
        bodyInput = findViewById(R.id.note_body_input);
        remarkInput = findViewById(R.id.note_remark_input);
        editorAttachments = findViewById(R.id.editor_attachments);
        readerAttachments = findViewById(R.id.reader_attachments);
        detailToolbar = findViewById(R.id.note_detail_toolbar);
        listToolbar = findViewById(R.id.note_toolbar);
        location = findViewById(R.id.note_location);
        rootDropTarget = findViewById(R.id.root_drop_target);
        detailToolbar.inflateMenu(R.menu.note_detail_toolbar);
        listToolbar.inflateMenu(R.menu.note_toolbar);
        adapter = new NoteAdapter(this);
        RecyclerView list = findViewById(R.id.note_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    private void configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.note_root), (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
    }

    private void configureActions() {
        // 根级返回退出模块，文件夹内返回根级；详情工具栏和系统返回键复用同一状态转换规则。
        listToolbar.setNavigationOnClickListener(view -> {
            if (currentFolder != null) openRoot(); else finish();
        });
        detailToolbar.setNavigationOnClickListener(view -> handleBack());
        detailToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_edit_note) { showEditor(); return true; }
            if (item.getItemId() == R.id.action_delete_note) { confirmDelete(); return true; }
            if (item.getItemId() == R.id.action_note_encryption) { toggleEncryption(); return true; }
            return false;
        });
        listToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_note_cloud_settings) {
                startActivity(new Intent(this, NoteCloudSettingsActivity.class));
                return true;
            }
            if (item.getItemId() == R.id.action_note_sync) {
                syncNow(true);
                return true;
            }
            return false;
        });
        findViewById(R.id.add_note).setOnClickListener(view -> newNote());
        findViewById(R.id.add_folder).setOnClickListener(view -> showFolderDialog(null));
        findViewById(R.id.save_note).setOnClickListener(view -> saveNote());
        findViewById(R.id.attach_file).setOnClickListener(view -> filePicker.launch(new String[]{"*/*"}));
        findViewById(R.id.fmt_heading).setOnClickListener(view -> prefixLine("## "));
        findViewById(R.id.fmt_bold).setOnClickListener(view -> wrapSelection("**", "**", "重点"));
        findViewById(R.id.fmt_list).setOnClickListener(view -> prefixLine("- "));
        findViewById(R.id.fmt_todo).setOnClickListener(view -> prefixLine("- [ ] "));
        findViewById(R.id.fmt_quote).setOnClickListener(view -> prefixLine("> "));
        findViewById(R.id.fmt_link).setOnClickListener(view -> wrapSelection("[", "](https://)", "链接文字"));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 每次输入都取消尚未执行的任务，只在用户短暂停顿后查询，降低连续键入产生的数据库压力。
                searchHandler.removeCallbacks(searchTask);
                searchHandler.postDelayed(searchTask, 180);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchHandler.removeCallbacks(searchTask);
                loadNotes();
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
                search.clearFocus();
                return true;
            }
            return false;
        });
        rootDropTarget.setOnDragListener((view, event) -> handleRootDrop(event));
    }

    private void loadNotes() {
        String query = search == null || search.getText() == null ? "" : search.getText().toString().trim();
        // 版本在提交后台任务前生成；即使旧任务稍后完成，也不能再覆盖新查询对应的界面。
        final int requestVersion = ++searchVersion;
        // 捕获发起请求时的文件夹，避免后台执行时读取一个已被导航操作改变的可变字段。
        final Long requestedFolderId = currentFolder == null ? null : currentFolder.id;
        executor.execute(() -> {
            boolean searching = !query.isEmpty();
            // 有关键词时跨全部文件夹搜索；无关键词时才展示当前文件夹，且根级附带文件夹列表。
            List<NoteRepository.Note> loaded = searching
                    ? repository.searchAll(query)
                    : repository.list(requestedFolderId, "");
            List<NoteRepository.Folder> loadedFolders = !searching && requestedFolderId == null
                    ? repository.listFolders() : new ArrayList<>();
            runOnUiThread(() -> {
                if (requestVersion != searchVersion) return;
                notes.clear(); notes.addAll(loaded);
                folders.clear(); folders.addAll(loadedFolders);
                adapter.submit(loadedFolders, loaded);
                empty.setVisibility(loaded.isEmpty() && loadedFolders.isEmpty() ? View.VISIBLE : View.GONE);
                location.setText(searching ? getString(R.string.note_search)
                        : currentFolder == null ? getString(R.string.unfiled_notes) : currentFolder.name);
                findViewById(R.id.add_folder).setVisibility(!searching && currentFolder == null
                        ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void newNote() {
        current = new NoteRepository.Note();
        current.title = "";
        current.content = "";
        current.body = "";
        current.folderId = currentFolder == null ? null : currentFolder.id;
        showDetail();
        showEditor();
    }

    @Override protected void onResume() {
        super.onResume();
        // 设置页可能刚改过配置或刚设好口令，重新读一次，避免继续使用旧凭据、旧策略或未解锁状态。
        executor.execute(() -> {
            cloudConfig = cloudRepository.load();
            loadKeyring();
            processTrashQueue();
        });
    }

    /**
     * 取回已解锁的加密凭据。
     *
     * <p>设置口令时把主密钥缓存进了 Keystore，因此日常不需要再输入；没有 keyring、或缓存不可用时视为
     * 未解锁——此时附件与正文都不会上传，而<b>不会</b>退化成明文上传。</p>
     */
    private void loadKeyring() {
        String text = cloudRepository.loadKeyringText();
        byte[] masterKey = cloudRepository.loadCachedMasterKey();
        if (text == null || text.isEmpty() || masterKey == null) {
            keyring = null;
            return;
        }
        try {
            keyring = Keyring.withMasterKey(text, masterKey);
        } catch (Keyring.WrongPasswordException error) {
            keyring = null;
        }
    }

    /**
     * 处理云端待过期队列：把不再需要的对象设为若干天后过期。
     *
     * <p>不立即删除是为了留一段误删缓冲，同时空间最终会被释放。失败项留在队列里，应用下次启动或再次
     * 删除笔记时重试——这样"断网时删笔记"也不会让云端对象永久残留。</p>
     */
    private void processTrashQueue() {
        CloudConfig config = cloudConfig;
        if (config == null || !config.isUsable()) {
            return;
        }
        executor.execute(() -> {
            List<NoteRepository.TrashItem> pending = repository.pendingTrash(50);
            if (pending.isEmpty()) {
                return;
            }
            QiniuManagementClient management = new QiniuManagementClient(config);
            for (NoteRepository.TrashItem item : pending) {
                if (!config.bucket.equals(item.bucket)) {
                    // 属于其他空间的历史条目：等配置切回那个空间再处理，避免在这里指向错误的空间。
                    continue;
                }
                try {
                    // meta/ 前缀的对象是墓碑载体，留 30 天让所有设备都能看到"已删除"；
                    // 内容对象（附件）7 天，尽快释放空间。
                    int days = item.objectKey.contains("/meta/") ? 30 : TRASH_EXPIRE_DAYS;
                    boolean handled = management.setDeleteAfterDays(item.objectKey, days);
                    if (handled) {
                        // 记下服务端给出的绝对过期时间：可据此确认 N 天是相对上传时间还是相对设置时间。
                        long expiration = management.stat(item.objectKey).expirationSeconds;
                        Log.i(TAG, "云端对象将过期删除 expiration=" + expiration + " key=" + item.objectKey);
                    }
                    repository.clearTrash(item.id);
                } catch (IOException error) {
                    repository.bumpTrashAttempt(item.id);
                    Log.w(TAG, "设置过期失败 key=" + item.objectKey + " " + error.getMessage());
                }
            }
        });
    }

    private static boolean hasRemoteAttachment(NoteRepository.Note note) {
        for (NoteRepository.Attachment attachment : note.attachments) {
            if (attachment.hasRemote()) {
                return true;
            }
        }
        return false;
    }

    private File cloudWorkRoot() {
        return new File(getCacheDir(), "note_cloud");
    }

    /**
     * 清理应用目录里已经没有任何记录指向的附件文件。
     *
     * <p>会出现这类残留的三种情况：上传失败或未解锁口令后用户直接放弃该笔记；用户添加附件但没保存就退出
     * （附件行从未落库）；以及本地模式下随笔记删除而遗留的副本——最后这条在改造前一直缺失，文件只增不减。</p>
     *
     * <p>只删除"没有任何记录引用"的文件，并且跳过 10 分钟内刚写入的：正在上传的副本此刻可能还没落库，
     * 误删会让上传直接失败。</p>
     */
    private void purgeOrphanFiles() {
        executor.execute(() -> {
            Set<String> referenced = referencedAttachmentFiles();
            long keepAfter = System.currentTimeMillis() - 10L * 60 * 1000;
            int removed = deleteUnreferenced(new File(getFilesDir(), "note_pending"), referenced, keepAfter)
                    + deleteUnreferenced(new File(getFilesDir(), "note_attachments"), referenced, keepAfter);
            if (removed > 0) {
                Log.i(TAG, "清理了 " + removed + " 个失去引用的附件文件");
            }
        });
    }

    /**
     * 汇总仍被引用的本地文件路径。
     *
     * <p>{@code local_path} 是绝对路径可以直接用；本应用 FileProvider 的 content URI 路径恰好就是
     * {@code filesDir} 下的相对路径（见 {@code file_paths.xml} 的 root 名），因此拼一下就能还原出真实文件，
     * 不需要按文件名猜测目录——附件现在放在"每附件一个子目录"里，按名字猜会猜错，进而误删。
     * 其他来源的 URI（比如用户选文件时的 SAF URI）不属于本应用目录，直接跳过。</p>
     */
    private Set<String> referencedAttachmentFiles() {
        Set<String> paths = new HashSet<>();
        String ownAuthority = "content://" + getPackageName() + ".files/";
        for (String value : repository.allAttachmentReferences()) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (!value.startsWith("content://")) {
                paths.add(value);
            } else if (value.startsWith(ownAuthority)) {
                try {
                    paths.add(new File(getFilesDir(), Uri.parse(value).getPath()).getAbsolutePath());
                } catch (RuntimeException ignored) {
                    // 解析不了就当作未引用，交给下一轮清理再判断。
                }
            }
        }
        return paths;
    }

    /** 递归删除没有任何记录引用的文件，并顺手清掉留下的空目录。 */
    private static int deleteUnreferenced(File directory, Set<String> referenced, long keepAfter) {
        File[] children = directory.listFiles();
        if (children == null) {
            return 0;
        }
        int removed = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                removed += deleteUnreferenced(child, referenced, keepAfter);
                File[] left = child.listFiles();
                if (left != null && left.length == 0) {
                    child.delete();
                }
                continue;
            }
            if (referenced.contains(child.getAbsolutePath()) || child.lastModified() > keepAfter) {
                continue;
            }
            if (child.delete()) {
                removed++;
            }
        }
        return removed;
    }

    @Override public void onOpen(NoteRepository.Note note) {
        executor.execute(() -> {
            NoteRepository.Note loaded = repository.get(note.id);
            runOnUiThread(() -> { current = loaded; showDetail(); showReader(); retryPendingUploads(); });
        });
    }

    @Override public void onOpenFolder(NoteRepository.Folder folder) {
        currentFolder = folder;
        search.setText("");
        loadNotes();
    }

    @Override public void onFolderOptions(NoteRepository.Folder folder) {
        new MaterialAlertDialogBuilder(this).setTitle(folder.name)
                .setItems(new String[]{getString(R.string.rename_folder), getString(R.string.delete_folder)},
                        (dialog, which) -> {
                            if (which == 0) showFolderDialog(folder); else confirmDeleteFolder(folder);
                        }).show();
    }

    @Override public void onNoteDragStarted(NoteRepository.Note note) {
        if (currentFolder != null) rootDropTarget.setVisibility(View.VISIBLE);
    }

    @Override public void onDropOnFolder(NoteRepository.Note note, NoteRepository.Folder folder) {
        moveNote(note, folder.id);
    }

    private void showDetail() {
        listPage.setVisibility(View.GONE);
        detailPage.setVisibility(View.VISIBLE);
    }

    private void showEditor() {
        if (current == null) return;
        // 编辑控件从已保存模型重新填充；工具栏插入的简化 Markdown 标记直接作用于正文输入框。
        reader.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        detailToolbar.setTitle(R.string.writing);
        titleInput.setText(current.title);
        bodyInput.setText(current.body);
        remarkInput.setText(current.content);
        renderEditorAttachments();
        updateEncryptionMenu();
        titleInput.requestFocus();
    }

    private void showReader() {
        if (current == null) return;
        // 阅读态不使用 WebView：MarkdownRenderer 生成原生 Span，LinkMovementMethod 负责点击 URLSpan。
        editor.setVisibility(View.GONE);
        reader.setVisibility(View.VISIBLE);
        detailToolbar.setTitle(R.string.reading);
        ((TextView)findViewById(R.id.note_read_title)).setText(current.title);
        TextView content = findViewById(R.id.note_read_content);
        content.setText(MarkdownRenderer.render(current.body));
        content.setMovementMethod(LinkMovementMethod.getInstance());
        renderReaderAttachments();
        // 备注只存在本机，单独一块显示；为空时连标题一起隐藏，避免留一片空白。
        TextView remark = findViewById(R.id.note_read_remark);
        TextView remarkLabel = findViewById(R.id.note_read_remark_label);
        boolean hasRemark = current.content != null && !current.content.trim().isEmpty();
        remark.setText(hasRemark ? current.content.trim() : "");
        remark.setVisibility(hasRemark ? View.VISIBLE : View.GONE);
        remarkLabel.setVisibility(hasRemark ? View.VISIBLE : View.GONE);
        updateEncryptionMenu();
    }

    private void saveNote() {
        String title = titleInput.getText().toString().trim();
        if (title.isEmpty()) {
            Snackbar.make(findViewById(R.id.note_root), R.string.note_title_required, Snackbar.LENGTH_SHORT).show();
            return;
        }
        CloudConfig config = cloudConfig;
        boolean cloudReady = config != null && config.isUsable();
        // 首次上传前问一次"这篇在云端加密吗"；问过之后不再打扰，想改可以在工具栏菜单里切。
        if (cloudReady && current.encrypted == NoteRepository.Note.ENCRYPTION_UNSET) {
            askEncryption(choice -> performSave(choice));
            return;
        }
        performSave(current.encrypted);
    }

    /**
     * 询问本篇在云端是否加密，只问一次。
     *
     * <p>不提供取消是刻意的：答案要写进笔记记录，跳过的话下次上传还会再问一遍。</p>
     */
    private void askEncryption(IntConsumer onChoice) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.note_encryption_ask_title)
                .setMessage(R.string.note_encryption_ask_message)
                .setNegativeButton(R.string.note_encryption_plain,
                        (dialog, which) -> onChoice.accept(NoteRepository.Note.ENCRYPTION_PLAIN))
                .setPositiveButton(R.string.note_encryption_encrypted,
                        (dialog, which) -> onChoice.accept(NoteRepository.Note.ENCRYPTION_ENCRYPTED))
                .setCancelable(false)
                .show();
    }

    /** 记录加密选择：先写内存，笔记已落库时同时写数据库，保证"问过了就记住"。 */
    private void applyEncryptionChoice(int choice) {
        if (current == null) {
            return;
        }
        current.encrypted = choice;
        if (current.id != 0) {
            final long noteId = current.id;
            executor.execute(() -> repository.updateNoteEncryption(noteId, choice));
        }
        updateEncryptionMenu();
    }

    /** 在"加密"与"明文"之间切换。尚未设定时先问一次。改动只影响下一次上传。 */
    private void toggleEncryption() {
        if (current == null) {
            return;
        }
        if (current.encrypted == NoteRepository.Note.ENCRYPTION_UNSET) {
            askEncryption(this::applyEncryptionChoice);
            return;
        }
        int next = current.encrypted == NoteRepository.Note.ENCRYPTION_ENCRYPTED
                ? NoteRepository.Note.ENCRYPTION_PLAIN
                : NoteRepository.Note.ENCRYPTION_ENCRYPTED;
        applyEncryptionChoice(next);
        Snackbar.make(findViewById(R.id.note_root),
                getString(R.string.note_encryption_changed, encryptionLabel(next)),
                Snackbar.LENGTH_LONG).show();
    }

    /** 菜单项标题直接显示当前选择，省得用户点进去才知道。 */
    private void updateEncryptionMenu() {
        MenuItem item = detailToolbar.getMenu().findItem(R.id.action_note_encryption);
        if (item == null) {
            return;
        }
        int value = current == null ? NoteRepository.Note.ENCRYPTION_UNSET : current.encrypted;
        item.setTitle(getString(R.string.note_encryption_menu, encryptionLabel(value)));
    }

    private String encryptionLabel(int value) {
        if (value == NoteRepository.Note.ENCRYPTION_PLAIN) {
            return getString(R.string.note_encryption_state_plain);
        }
        if (value == NoteRepository.Note.ENCRYPTION_ENCRYPTED) {
            return getString(R.string.note_encryption_state_encrypted);
        }
        return getString(R.string.note_encryption_state_unset);
    }

    private void performSave(int encryption) {
        current.title = titleInput.getText().toString().trim();
        current.body = bodyInput.getText().toString();
        current.content = remarkInput.getText().toString();
        current.encrypted = encryption;
        current.syncState = NoteRepository.Note.SYNC_PENDING;
        // 保存及附件重建在后台事务中完成；成功后重读附件主键，再切回阅读态并刷新列表。
        executor.execute(() -> {
            repository.save(current);
            NoteRepository.Note loaded = repository.get(current.id);
            runOnUiThread(() -> {
                current = loaded;
                Snackbar.make(findViewById(R.id.note_root), R.string.note_saved, Snackbar.LENGTH_SHORT).show();
                showReader();
                loadNotes();
            });
            // 每次保存都重传一遍：正文只有几 KB，而"仅变化时上传"会让附件清单长期停在旧版本，
            // 结果是换设备恢复时正文回来了、附件却认不出来。省下的流量不值得这个风险。
            syncNoteBody(loaded);
        });
    }

    /**
     * 把正文同步到云端（加密后上传）。
     *
     * <p>只上传标题与正文：{@code content}（备注）永远不会进入这里的 payload，所以它在实现层面就不可能
     * 被上传出去。</p>
     */
    private void syncNoteBody(NoteRepository.Note note) {
        CloudConfig config = cloudConfig;
        Keyring unlocked = keyring;
        if (note == null || !note.canSync() || config == null || !config.isUsable()) {
            return;
        }
        final boolean encrypt = note.encryptsUpload();
        if (encrypt && unlocked == null) {
            // 只有确实有内容需要同步时才提示，避免每次保存都弹一条没有信息量的警告。
            boolean worthSyncing = (note.body != null && !note.body.trim().isEmpty())
                    || hasRemoteAttachment(note);
            if (worthSyncing) {
                runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                        R.string.note_body_sync_skipped, Snackbar.LENGTH_LONG).show());
            } else {
                Log.d(TAG, "本篇设为加密但未解锁口令，且没有需要同步的内容");
            }
            return;
        }
        CloudClient client = new CloudClient(config, cloudWorkRoot());
        cloudClient = client;
        try {
            NoteSyncClient sync = new NoteSyncClient(config, client, cloudWorkRoot());
            sync.upload(unlocked, payloadOf(note, false, 0), encrypt);
            repository.markNoteSynced(note.syncId, System.currentTimeMillis());
            runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                    R.string.note_body_synced, Snackbar.LENGTH_SHORT).show());
        } catch (IOException error) {
            repository.markNoteSyncFailed(note.syncId);
            Log.w(TAG, "正文同步失败: " + error.getMessage(), error);
            runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                    R.string.note_body_sync_failed, Snackbar.LENGTH_LONG).show());
        } finally {
            cloudClient = null;
        }
    }

    /**
     * 构建笔记的云端 payload。
     *
     * @param deleted   构建墓碑时为 true；此时 updatedAt 取删除时间，让"删了又改可复活"的比较有基准
     */
    private NoteSyncClient.Payload payloadOf(NoteRepository.Note note, boolean deleted, long deletedAt) {
        NoteSyncClient.Payload payload = new NoteSyncClient.Payload();
        payload.syncId = note.syncId;
        payload.title = note.title;
        payload.body = note.body;
        payload.folderName = note.folderId == null ? "" : repository.folderName(note.folderId);
        payload.createdAt = note.createdAt;
        payload.updatedAt = deleted ? deletedAt : note.updatedAt;
        payload.deleted = deleted;
        payload.deletedAt = deleted ? deletedAt : 0;
        payload.deviceId = cloudRepository.loadOrCreateDeviceId();
        // 附件清单一并上传：少了它，换设备恢复时正文回来了却不知道附件属于哪篇笔记，附件等于丢失。
        for (NoteRepository.Attachment attachment : note.attachments) {
            if (!attachment.hasRemote()) {
                continue;
            }
            NoteSyncClient.Payload.AttachmentRef ref = new NoteSyncClient.Payload.AttachmentRef();
            ref.name = attachment.name;
            ref.mimeType = attachment.mimeType;
            ref.remoteKey = attachment.remoteKey;
            ref.md5 = attachment.md5;
            ref.plainSha256 = attachment.plainSha256;
            ref.size = attachment.size;
            ref.encrypted = attachment.encrypted;
            payload.attachments.add(ref);
        }
        return payload;
    }

    /** 云端 payload → 本地笔记。加密标记取自下载时的内容嗅探，而不是本地残留的旧值。 */
    private static NoteRepository.Note toNote(NoteSyncClient.Payload payload) {
        NoteRepository.Note note = new NoteRepository.Note();
        note.title = payload.title;
        note.body = payload.body;
        note.content = "";
        note.syncId = payload.syncId;
        note.createdAt = payload.createdAt;
        note.updatedAt = payload.updatedAt;
        note.syncState = NoteRepository.Note.SYNCED;
        note.encrypted = payload.encrypted
                ? NoteRepository.Note.ENCRYPTION_ENCRYPTED
                : NoteRepository.Note.ENCRYPTION_PLAIN;
        return note;
    }

    /** 按名字落文件夹；没有就建一个，让文件夹结构随同步自然双向。 */
    private Long resolveFolder(NoteRepository repository, String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        Long folderId = repository.findFolderIdByName(name);
        return folderId != null ? folderId : repository.createFolder(name.trim());
    }

    private void prefixLine(String prefix) {
        Editable text = bodyInput.getText();
        int cursor = Math.max(0, bodyInput.getSelectionStart());
        int lineStart = cursor;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        text.insert(lineStart, prefix);
        bodyInput.setSelection(cursor + prefix.length());
    }

    private void wrapSelection(String before, String after, String placeholder) {
        Editable text = bodyInput.getText();
        int start = Math.max(0, bodyInput.getSelectionStart());
        int end = Math.max(start, bodyInput.getSelectionEnd());
        String selected = start == end ? placeholder : text.subSequence(start, end).toString();
        text.replace(start, end, before + selected + after);
        bodyInput.setSelection(start + before.length(), start + before.length() + selected.length());
    }

    /**
     * 选文件后的入口。
     *
     * <p>元数据在主线程读取，随后整段处理交给后台执行器，并在后台才决定走云端还是本地：云同步配置可能
     * 还没加载完，过早判断会把附件错误地按本地方式保存。</p>
     */
    private void onFilePicked(Uri uri) {
        if (uri == null || current == null) return;
        String name = "附件";
        long size = 0;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        }
        final String fileName = name;
        final long sourceSize = size;
        final String mimeType = getContentResolver().getType(uri);
        persistSourcePermission(uri);
        executor.execute(() -> {
            CloudConfig config = cloudConfig == null ? cloudRepository.load() : cloudConfig;
            cloudConfig = config;
            if (config.isUsable()) {
                importToCloud(uri, fileName, sourceSize, mimeType, config);
            } else {
                importLocally(uri, fileName, sourceSize, mimeType);
            }
        });
    }

    /**
     * 未启用云同步时的原有行为：复制进应用目录，此后不再依赖源文件，因此源文件被移动或删除都不影响。
     */
    private void importLocally(Uri uri, String fileName, long sourceSize, String mimeType) {
        try {
            // 与云端模式保持一致：每个附件一个子目录，文件名保留原始名。
            File directory = new File(getFilesDir(), "note_attachments/" + UUID.randomUUID());
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("无法创建附件目录");
            }
            File destination = new File(directory, safeFileName(fileName));
            long copied = copyTo(uri, destination);
            Uri managedUri = FileProvider.getUriForFile(this, getPackageName() + ".files", destination);
            long finalSize = copied > 0 ? copied : sourceSize;
            runOnUiThread(() -> {
                current.attachments.add(NoteRepository.Attachment.local(
                        managedUri.toString(), fileName, mimeType, finalSize));
                renderEditorAttachments();
                Snackbar.make(findViewById(R.id.note_root), R.string.attachment_imported,
                        Snackbar.LENGTH_SHORT).show();
            });
        } catch (Exception error) {
            runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                    messageOf(error), Snackbar.LENGTH_LONG).show());
        }
    }

    /**
     * 启用云同步时的行为：先把文件复制进应用私有目录，再上传。
     *
     * <p>这份私有副本不是多余的拷贝，而是「失败可重试」的载体——SAF 返回的 content URI 在进程重启后
     * 通常失效，没有本地副本就无法真正重试。上传成功且校验通过后，它会立刻被删除。</p>
     */
    private void importToCloud(Uri source, String fileName, long sourceSize, String mimeType,
                               CloudConfig config) {
        File pending = null;
        try {
            // 每个附件一个子目录，文件名直接用原始名：把它交给外部应用打开时，对方看到的是你的文件名，
            // 而不是"一串 uuid_原名"。子目录本身保证同名文件不会互相覆盖。
            File directory = new File(getFilesDir(), "note_pending/" + UUID.randomUUID());
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("无法创建附件目录");
            }
            pending = new File(directory, safeFileName(fileName));
            long copied = copyTo(source, pending);
            NoteRepository.Attachment attachment = new NoteRepository.Attachment(null, 0,
                    source.toString(), fileName, mimeType, copied > 0 ? copied : sourceSize,
                    pending.getAbsolutePath(), null, null, null,
                    NoteRepository.Attachment.STATE_PENDING);
            final NoteRepository.Attachment created = attachment;
            runOnUiThread(() -> {
                if (current == null) {
                    // 用户已经离开这篇笔记：附件行从未落库，此刻上传只会造出一个没人引用的云端对象。
                    // 本地那份副本交给孤儿清理处理。
                    return;
                }
                current.attachments.add(created);
                renderEditorAttachments();
                // 本篇还没决定加密方式时先问一次；答案同时决定正文与附件的上传方式。
                if (current.encrypted == NoteRepository.Note.ENCRYPTION_UNSET) {
                    askEncryption(choice -> {
                        applyEncryptionChoice(choice);
                        upload(created, config, true, choice == NoteRepository.Note.ENCRYPTION_ENCRYPTED);
                    });
                } else {
                    upload(created, config, true, current.encryptsUpload());
                }
            });
        } catch (Exception error) {
            if (pending != null) {
                pending.delete();
            }
            runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                    messageOf(error), Snackbar.LENGTH_LONG).show());
        }
    }

    /**
     * 上传单个附件。
     *
     * <p>校验被放在方法内部：只有服务端 ETag 与本地 MD5 完全一致才算成功。删除本地文件是这一判断的
     * 下游动作，因此不能把校验交给调用方，否则任何一处遗漏都会变成「以为传上去了，其实没有」。</p>
     */
    private void upload(NoteRepository.Attachment attachment, CloudConfig config, boolean announce,
                        boolean encrypt) {
        executor.execute(() -> {
            String localPath = attachment.localPath;
            if (localPath == null || localPath.isEmpty()) {
                return;
            }
            File local = new File(localPath);
            if (!local.isFile()) {
                markUploadFailed(attachment);
                return;
            }
            Keyring unlocked = keyring;
            if (encrypt && unlocked == null) {
                // 未解锁时保持"待上传"而不是标记失败：本地副本还在，设置口令后重试即可。
                runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                        R.string.cloud_passphrase_absent, Snackbar.LENGTH_LONG).show());
                return;
            }
            if (announce) {
                runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                        R.string.attachment_uploading, Snackbar.LENGTH_SHORT).show());
            }
            CloudClient client = new CloudClient(config, cloudWorkRoot());
            cloudClient = client;
            try {
                // 对象键用原始文件名，方便在控制台辨认；重名时追加序号，绝不让新文件悄悄覆盖云端已有文件。
                String key = client.availableKey(
                        ObjectKeys.attachmentKey(config.normalizedPrefix(), attachment.name));
                // 内容密钥由对象键派生：键唯一且已存在本地，下载时能重新算出来，因此不必另存密钥。
                byte[] fileKey = encrypt
                        ? NoteCipher.objectKey(unlocked.masterKey(), "file", key) : null;
                String contentType = attachment.mimeType == null || attachment.mimeType.isEmpty()
                        ? "application/octet-stream" : attachment.mimeType;
                CloudClient.UploadResult result = client.putObject(key, local, contentType, null, fileKey);
                if (!result.isVerified()) {
                    throw new IOException("校验未通过：服务端 ETag 与本地内容摘要不一致");
                }
                attachment.remoteKey = key;
                attachment.remoteBucket = config.bucket;
                attachment.md5 = result.md5;
                attachment.plainSha256 = result.plainSha256;
                attachment.encrypted = encrypt;
                attachment.uploadState = NoteRepository.Attachment.STATE_UPLOADED;
                // 云端已有校验过的副本，私有副本完成使命，不再在手机上留第二份。
                local.delete();
                attachment.localPath = null;
                repository.updateAttachmentUpload(attachment.localId, key, config.bucket, result.md5,
                        NoteRepository.Attachment.STATE_UPLOADED, null, encrypt, result.plainSha256);
                handleLocalFileAfterUpload(attachment, config);
            } catch (IOException error) {
                markUploadFailed(attachment);
                Log.w(TAG, "附件上传失败: " + error.getMessage(), error);
                final String detail = messageOf(error);
                runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                        getString(R.string.attachment_upload_failed_detail, detail),
                        Snackbar.LENGTH_LONG).show());
            } finally {
                cloudClient = null;
            }
        });
    }

    /**
     * 上传成功且校验通过后处理手机上的原文件。
     *
     * <p>应用目录里的私有副本在上传流程中已经删除，这里只针对用户的原始文件，按配置的三档策略执行：
     * 保留 / 总是删除 / 每次询问。任何一档都建立在上面的校验通过之上。</p>
     */
    private void handleLocalFileAfterUpload(NoteRepository.Attachment attachment, CloudConfig config) {
        if (attachment.uri == null || attachment.uri.isEmpty()) {
            showUploadResult(R.string.attachment_uploaded_keep_local, attachment);
            return;
        }
        switch (config.deleteLocalMode) {
            case CloudConfig.DELETE_KEEP:
                showUploadResult(R.string.attachment_uploaded_keep_local, attachment);
                break;
            case CloudConfig.DELETE_ASK:
                runOnUiThread(() -> enqueueDeleteAsk(attachment));
                break;
            default:
                boolean removed = deleteOriginalFile(attachment.uri);
                if (removed) {
                    attachment.uri = "";
                }
                showUploadResult(removed ? R.string.attachment_uploaded_local_deleted
                        : R.string.attachment_local_delete_failed, attachment);
                break;
        }
    }

    private void showUploadResult(int messageId, NoteRepository.Attachment attachment) {
        runOnUiThread(() -> {
            renderEditorAttachments();
            if (reader.getVisibility() == View.VISIBLE) {
                renderReaderAttachments();
            }
            Snackbar.make(findViewById(R.id.note_root), messageId, Snackbar.LENGTH_LONG).show();
        });
    }

    private void enqueueDeleteAsk(NoteRepository.Attachment attachment) {
        deleteAsks.add(attachment);
        showNextDeleteAsk();
    }

    /**
     * 串行展示「是否删除本地文件」的询问。
     *
     * <p>多个附件可能同时上传完成，若各自弹窗会叠在一起、用户也不知道在回答哪一个。队列由弹窗的
     * dismiss 驱动：上一个确实消失之后才问下一个。</p>
     */
    private void showNextDeleteAsk() {
        if (deleteAskShowing || deleteAsks.isEmpty()) {
            return;
        }
        final NoteRepository.Attachment attachment = deleteAsks.poll();
        deleteAskShowing = true;
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.attachment_ask_delete_title)
                .setMessage(getString(R.string.attachment_ask_delete_message, attachment.name))
                .setNegativeButton(R.string.attachment_ask_delete_keep, null)
                .setPositiveButton(R.string.delete, null)
                .setCancelable(false)
                .create();
        dialog.setOnDismissListener(dismissed -> {
            deleteAskShowing = false;
            showNextDeleteAsk();
        });
        dialog.setOnShowListener(shown -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> dialog.dismiss());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                // 删除源文件是磁盘与 Provider 操作，放到后台线程执行。
                executor.execute(() -> {
                    boolean removed = deleteOriginalFile(attachment.uri);
                    if (removed) {
                        attachment.uri = "";
                    }
                    showUploadResult(removed ? R.string.attachment_uploaded_local_deleted
                            : R.string.attachment_local_delete_failed, attachment);
                });
            });
        });
        dialog.show();
    }

    /** 上传失败只改状态，本地副本原样保留，供稍后重试。 */
    private void markUploadFailed(NoteRepository.Attachment attachment) {
        attachment.uploadState = NoteRepository.Attachment.STATE_FAILED;
        repository.updateAttachmentUpload(attachment.localId, null, null, null,
                NoteRepository.Attachment.STATE_FAILED, attachment.localPath,
                attachment.encrypted, attachment.plainSha256);
        runOnUiThread(this::renderEditorAttachments);
    }

    /**
     * 尝试删除手机上的原始文件。
     *
     * <p>能否成功取决于文件来源：文件管理器、下载目录返回的文档 URI 一般可删，而网盘或聊天软件提供的
     * 虚拟文档往往不支持。失败时返回 false，由调用方给出「请手动清理」，绝不把未删除当成功上报。</p>
     */
    private boolean deleteOriginalFile(String uriString) {
        if (uriString == null || uriString.isEmpty()) {
            return false;
        }
        Uri uri = Uri.parse(uriString);
        try {
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                return path != null && new File(path).delete();
            }
            return DocumentsContract.deleteDocument(getContentResolver(), uri);
        } catch (Exception error) {
            return false;
        }
    }

    /**
     * 持久化对源文件的访问权限。
     *
     * <p>SAF 授权默认只在本次任务内有效，而「稍后重试上传」与「删除原文件」都发生在其后。写权限拿不到时
     * 退化为只读授权：上传仍可完成，只是删除原文件会失败并如实提示。</p>
     */
    private void persistSourcePermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException error) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // 无法持久化时本次流程依然可用，只是重启后不能重试或删除源文件。
            }
        }
    }

    private long copyTo(Uri source, File destination) throws IOException {
        long copied = 0;
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("无法读取源文件");
            }
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                copied += count;
            }
        }
        return copied;
    }

    private static String safeFileName(String name) {
        String value = name == null || name.trim().isEmpty() ? "附件" : name.trim();
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }

    /** 附件状态文案。历史遗留的仅本地附件没有云副本也没有待上传副本，不显示任何标记。 */
    private String attachmentStatus(NoteRepository.Attachment attachment) {
        if (attachment.hasRemote()) {
            return getString(R.string.attachment_uploaded);
        }
        if (attachment.isAwaitingUpload()) {
            return getString(attachment.uploadState == NoteRepository.Attachment.STATE_FAILED
                    ? R.string.attachment_upload_failed : R.string.attachment_pending);
        }
        return "";
    }

    private void renderEditorAttachments() {
        // 上传是异步的，回调可能在用户已经退回列表页之后到达，此时没有任何编辑态可渲染。
        if (current == null) return;
        editorAttachments.removeAllViews();
        for (NoteRepository.Attachment attachment : new ArrayList<>(current.attachments)) {
            MaterialButton button = new MaterialButton(this);
            String status = attachmentStatus(attachment);
            button.setText(status.isEmpty()
                    ? attachment.name + "  ×"
                    : attachment.name + " · " + status + "  ×");
            button.setOnClickListener(view -> {
                current.attachments.remove(attachment);
                renderEditorAttachments();
            });
            editorAttachments.addView(button);
        }
    }

    private void renderReaderAttachments() {
        if (current == null) return;
        readerAttachments.removeAllViews();
        for (NoteRepository.Attachment attachment : current.attachments) {
            MaterialButton button = new MaterialButton(this);
            String status = attachmentStatus(attachment);
            button.setText(status.isEmpty() ? attachment.name : attachment.name + " · " + status);
            button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            button.setOnClickListener(view -> openAttachment(attachment));
            readerAttachments.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        readerAttachments.setVisibility(current.attachments.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * 打开附件。
     *
     * <p>按「待上传重试 → 本地副本 → 原始文件 → 云端按需下载」的顺序选择来源。逐级回退而不是只看一个
     * 字段，是因为附件在不同阶段可能只存在其中一种副本：刚添加时只有私有副本，上传后只剩云端，
     * 而历史附件只有本机文件。</p>
     */
    private void openAttachment(NoteRepository.Attachment attachment) {
        CloudConfig config = cloudConfig;
        if (attachment.isAwaitingUpload()) {
            if (config != null && config.isUsable()) {
                upload(attachment, config, true, current == null || current.encryptsUpload());
                return;
            }
            // 云同步在添加之后被关闭：本地副本还在，先让用户能打开它，而不是只给一句提示。
            if (attachment.localPath != null && !attachment.localPath.isEmpty()) {
                File pending = new File(attachment.localPath);
                if (pending.isFile() && openLocalFile(pending, attachment.mimeType)) {
                    return;
                }
            }
            Snackbar.make(findViewById(R.id.note_root), R.string.attachment_cloud_disabled,
                    Snackbar.LENGTH_LONG).show();
            return;
        }
        if (attachment.localPath != null && !attachment.localPath.isEmpty()) {
            File local = new File(attachment.localPath);
            if (local.isFile() && openLocalFile(local, attachment.mimeType)) {
                return;
            }
        }
        if (attachment.uri != null && !attachment.uri.isEmpty() && openUri(attachment.uri, attachment.mimeType)) {
            return;
        }
        if (attachment.hasRemote()) {
            if (config == null || !config.isUsable()) {
                Snackbar.make(findViewById(R.id.note_root), R.string.attachment_cloud_disabled,
                        Snackbar.LENGTH_LONG).show();
                return;
            }
            downloadThenOpen(attachment, config);
            return;
        }
        Snackbar.make(findViewById(R.id.note_root), R.string.file_open_failed, Snackbar.LENGTH_LONG).show();
    }

    private boolean openLocalFile(File file, String mimeType) {
        try {
            Uri shareUri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(shareUri, mimeType == null || mimeType.isEmpty() ? "*/*" : mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private boolean openUri(String uri, String mimeType) {
        try {
            Uri target = Uri.parse(uri);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(target, mimeType == null || mimeType.isEmpty() ? "*/*" : mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException error) {
            return false;
        }
    }

    /**
     * 本地没有副本时从云端取回，下载到缓存目录再交给外部应用打开。
     *
     * <p>缓存文件不在这里立刻删除：外部阅读器可能仍在按需读取。清理由 {@link #clearStaleCache()} 在下次
     * 进入本页面时按时间清理，兼顾「即用即焚」与「打开后还能翻页」。</p>
     */
    private void downloadThenOpen(NoteRepository.Attachment attachment, CloudConfig config) {
        final Keyring unlocked = keyring;
        Snackbar.make(findViewById(R.id.note_root), R.string.attachment_downloading,
                Snackbar.LENGTH_SHORT).show();
        executor.execute(() -> {
            File directory = new File(getCacheDir(), "note_cache/" + attachment.localId);
            if (!directory.exists() && !directory.mkdirs()) {
                runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                        getString(R.string.attachment_download_failed, "无法创建缓存目录"),
                        Snackbar.LENGTH_LONG).show());
                return;
            }
            File target = new File(directory, safeFileName(attachment.name));
            CloudClient client = new CloudClient(config, cloudWorkRoot());
            cloudClient = client;
            try {
                // 不依赖本地那个加密标记：由内容自己说明是否加密。标记可能是旧数据或来自另一台设备，
                // 而魔数永远准确。没有密钥时若内容是密文，会得到明确的错误而不是一串乱码。
                byte[] fileKey = unlocked == null ? null
                        : NoteCipher.objectKey(unlocked.masterKey(), "file", attachment.remoteKey);
                boolean decrypted = client.getObject(attachment.remoteKey, target, null, fileKey);
                if (decrypted && !verifyPlaintext(attachment, target)) {
                    target.delete();
                    runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                            getString(R.string.attachment_download_failed, "内容校验未通过，已丢弃"),
                            Snackbar.LENGTH_LONG).show());
                    return;
                }
                runOnUiThread(() -> {
                    if (!openLocalFile(target, attachment.mimeType)) {
                        Snackbar.make(findViewById(R.id.note_root), R.string.file_open_failed,
                                Snackbar.LENGTH_LONG).show();
                    }
                });
            } catch (IOException error) {
                final String detail = messageOf(error);
                runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                        getString(R.string.attachment_download_failed, detail), Snackbar.LENGTH_LONG).show());
            } finally {
                cloudClient = null;
            }
        });
    }

    /**
     * 解密后核对明文摘要。
     *
     * <p>ETag 校验只能证明"密文完整到达"，证明不了"解出来的就是原文件"。加密上传时记录了明文 SHA-256，
     * 这里比对一次，把解密实现或密钥派生出错这类静默损坏挡在打开文件之前。</p>
     */
    private static boolean verifyPlaintext(NoteRepository.Attachment attachment, File file) {
        if (attachment.plainSha256 == null || attachment.plainSha256.isEmpty()) {
            return true;
        }
        try {
            return attachment.plainSha256.equalsIgnoreCase(NoteCipher.sha256Hex(readAll(file)));
        } catch (IOException error) {
            return false;
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream input = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int count;
            while ((count = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
            }
            return buffer.toByteArray();
        }
    }

    /** 清理上次取件留下的缓存。保留一段时间是为了让仍在读取的外部阅读器不被打断。 */
    private void clearStaleCache() {
        executor.execute(() -> {
            long deadline = System.currentTimeMillis() - 6L * 60 * 60 * 1000;
            int removed = deleteStale(new File(getCacheDir(), "note_cache"), deadline);
            if (removed > 0) {
                Log.i(TAG, "清理了 " + removed + " 个过期的附件缓存");
            }
        });
    }

    /** 递归清理过期缓存文件，并回收清空后的子目录。 */
    private static int deleteStale(File directory, long deadline) {
        File[] children = directory.listFiles();
        if (children == null) {
            return 0;
        }
        int removed = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                removed += deleteStale(child, deadline);
                File[] left = child.listFiles();
                if (left != null && left.length == 0) {
                    child.delete();
                }
                continue;
            }
            if (child.lastModified() < deadline && child.delete()) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 打开笔记时补一次上传尝试，覆盖「断网时添加、之后一直没打开过」的附件。
     * 每次进入最多尝试一次，失败后状态仍是待上传，不会形成反复重试。
     */
    private void retryPendingUploads() {
        CloudConfig config = cloudConfig;
        if (config == null || !config.isUsable() || current == null) {
            return;
        }
        for (NoteRepository.Attachment attachment : new ArrayList<>(current.attachments)) {
            if (attachment.isAwaitingUpload()) {
                upload(attachment, config, false, current.encryptsUpload());
            }
        }
    }

    private void confirmDelete() {
        if (current == null || current.id == 0) { showList(); return; }
        final NoteRepository.Note target = current;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_note_title)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> executor.execute(() -> {
                    CloudConfig config = cloudConfig;
                    boolean cloudReady = config != null && config.isUsable();
                    String bucket = cloudReady ? config.bucket : null;
                    // 正文对象键用 syncId 现算：它不依赖云端列表，删除时不需要联网。
                    String metaKey = cloudReady && target.canSync()
                            ? ObjectKeys.noteMetaKey(config.normalizedPrefix(), target.syncId) : null;
                    long deletedAt = System.currentTimeMillis();
                    // 墓碑要带完整 payload（含附件清单），否则回收站只能找回名字。
                    String payloadJson = target.canSync() ? payloadOf(target, true, deletedAt).toJson() : "";
                    NoteRepository.Tombstone tombstone = repository.delete(target.id, bucket, metaKey,
                            cloudRepository.loadOrCreateDeviceId(), payloadJson, target.encryptsUpload());
                    runOnUiThread(() -> { showList(); loadNotes(); });
                    if (tombstone != null) {
                        uploadTombstone(tombstone);
                    }
                    // 登记完立刻尝试把云端对象设为过期；失败会留在队列里等下次。
                    processTrashQueue();
                })).show();
    }

    /** 把墓碑上传到云端（完整 payload + 删除标记）。失败不打紧：墓碑行还在，下次同步会重试。 */
    private void uploadTombstone(NoteRepository.Tombstone tombstone) {
        CloudConfig config = cloudConfig;
        if (config == null || !config.isUsable()) {
            return;
        }
        executor.execute(() -> {
            try {
                Keyring unlocked = tombstone.encrypted ? keyring : null;
                if (tombstone.encrypted && unlocked == null) {
                    throw new IOException("尚未解锁口令");
                }
                NoteSyncClient.Payload payload = NoteSyncClient.Payload.fromJson(tombstone.payloadJson);
                payload.deleted = true;
                payload.deletedAt = tombstone.deletedAt;
                payload.deviceId = tombstone.deviceId;
                CloudClient client = new CloudClient(config, cloudWorkRoot());
                cloudClient = client;
                try {
                    new NoteSyncClient(config, client, cloudWorkRoot())
                            .upload(unlocked, payload, tombstone.encrypted);
                } finally {
                    cloudClient = null;
                }
                repository.markTombstoneUploaded(tombstone.syncId);
            } catch (IOException error) {
                Log.w(TAG, "墓碑上传失败: " + tombstone.syncId + " " + error.getMessage());
            }
        });
    }

    /**
     * 完整同步：拉取（正文 + 墓碑）→ 推送（未同步的正文与墓碑）→ 清理待过期队列。
     *
     * <p>两端跑同一套规则；同步顺序无关紧要，因为每个 meta 对象键就是笔记的唯一身份，
     * 合并按 updatedAt 后写赢。触发点：进入页面、手动菜单、保存后的正文推送。</p>
     */
    private void syncNow(boolean announce) {
        executor.execute(() -> {
            CloudConfig config = cloudConfig;
            if (config == null || !config.isUsable()) {
                if (announce) {
                    runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                            R.string.attachment_cloud_disabled, Snackbar.LENGTH_SHORT).show());
                }
                return;
            }
            final Keyring unlocked = keyring;
            CloudClient client = new CloudClient(config, cloudWorkRoot());
            cloudClient = client;
            int inserted = 0;
            int updated = 0;
            int deleted = 0;
            int pushed = 0;
            int failed = 0;
            try {
                NoteSyncClient sync = new NoteSyncClient(config, client, cloudWorkRoot());
                // ① 拉取：别人改的、删的，都落过来。单篇失败不中断，否则一篇加密笔记锁不住整批。
                for (String syncId : sync.listSyncIds(1000)) {                    try {
                        NoteSyncClient.Payload payload = sync.download(unlocked, syncId);
                        Long folderId = resolveFolder(repository, payload.folderName);
                        if (payload.deleted) {
                            if (repository.applyCloudDeletion(payload.syncId, payload.title,
                                    payload.deletedAt, payload.toJson(), payload.encrypted)) {
                                deleted++;
                            }
                            continue;
                        }
                        boolean existed = repository.findBySyncId(syncId) != null;
                        if (repository.mergeFromCloud(toNote(payload), folderId)) {
                            if (existed) {
                                updated++;
                            } else {
                                inserted++;
                            }
                        }
                        repository.recoverAttachments(syncId, toAttachments(payload, config.bucket));
                    } catch (IOException error) {
                        failed++;
                        Log.w(TAG, "拉取失败 " + syncId + ": " + error.getMessage());
                    }
                }
                // ② 推送正文：跳过已同步的与已删除的——墓碑在手的笔记绝不能再传上去。
                for (NoteRepository.Note note : repository.listSyncableNotes()) {
                    if (note.syncState == NoteRepository.Note.SYNCED
                            || repository.findTombstone(note.syncId) != null) {
                        continue;
                    }
                    if (note.encryptsUpload() && unlocked == null) {
                        failed++;
                        continue;
                    }
                    try {
                        sync.upload(unlocked, payloadOf(note, false, 0), note.encryptsUpload());
                        repository.markNoteSynced(note.syncId, System.currentTimeMillis());
                        pushed++;
                    } catch (IOException error) {
                        failed++;
                        repository.markNoteSyncFailed(note.syncId);
                        Log.w(TAG, "推送失败 " + note.syncId + ": " + error.getMessage());
                    }
                }
                // ②b 推送墓碑：删除传播的载体。
                for (NoteRepository.Tombstone tombstone : repository.unsyncedTombstones()) {
                    try {
                        Keyring unlockedFor = tombstone.encrypted ? unlocked : null;
                        if (tombstone.encrypted && unlockedFor == null) {
                            throw new IOException("尚未解锁口令");
                        }
                        NoteSyncClient.Payload payload = NoteSyncClient.Payload.fromJson(tombstone.payloadJson);
                        payload.deleted = true;
                        payload.deletedAt = tombstone.deletedAt;
                        payload.deviceId = tombstone.deviceId;
                        sync.upload(unlockedFor, payload, tombstone.encrypted);
                        repository.markTombstoneUploaded(tombstone.syncId);
                    } catch (IOException error) {
                        failed++;
                        Log.w(TAG, "墓碑上传失败 " + tombstone.syncId + ": " + error.getMessage());
                    }
                }
                // ③ 清理
                processTrashQueue();
            } catch (IOException error) {
                // 整批中断（例如列举失败）也要有反馈；单项失败已在上面计入 failed。
                failed++;
                Log.w(TAG, "同步中断: " + error.getMessage());
            } finally {
                cloudClient = null;
            }
            final int newNotes = inserted;
            final int updatedNotes = updated;
            final int deletedNotes = deleted;
            final int pushedNotes = pushed;
            final int failedItems = failed;
            runOnUiThread(() -> {
                loadNotes();
                if (announce) {
                    String summary = getString(R.string.sync_result, newNotes, updatedNotes,
                            deletedNotes, pushedNotes);
                    if (failedItems > 0) {
                        summary += "\n" + getString(R.string.sync_failed_partial, failedItems);
                    }
                    Snackbar.make(findViewById(R.id.note_root), summary,
                            Snackbar.LENGTH_LONG).show();
                }
            });
        });
    }

    /** 从云端 payload 还原本地附件记录（只建元数据，文件按需下载）。 */
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

    private void handleBack() {
        if (detailPage.getVisibility() == View.VISIBLE) {
            // 已保存笔记从编辑态先退回阅读态；新笔记尚无可阅读版本，直接退回列表。
            if (editor.getVisibility() == View.VISIBLE && current != null && current.id != 0) showReader();
            else showList();
        } else if (currentFolder != null) {
            openRoot();
        } else {
            finish();
        }
    }

    private void showList() {
        detailPage.setVisibility(View.GONE);
        listPage.setVisibility(View.VISIBLE);
        current = null;
    }

    private void openRoot() {
        currentFolder = null;
        rootDropTarget.setVisibility(View.GONE);
        search.setText("");
        loadNotes();
    }

    private void showFolderDialog(NoteRepository.Folder folder) {
        com.google.android.material.textfield.TextInputLayout layout = new com.google.android.material.textfield.TextInputLayout(
                this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, 0, padding, 0);
        layout.setHint(R.string.folder_name);
        com.google.android.material.textfield.TextInputEditText input =
                new com.google.android.material.textfield.TextInputEditText(layout.getContext());
        if (folder != null) input.setText(folder.name);
        layout.addView(input);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(folder == null ? R.string.new_folder : R.string.rename_folder)
                .setView(layout).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null).create();
        dialog.setOnShowListener(unused -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = input.getText() == null ? "" : input.getText().toString().trim();
                    if (name.isEmpty()) { layout.setError(getString(R.string.folder_name)); return; }
                    dialog.dismiss();
                    executor.execute(() -> {
                        if (folder == null) repository.createFolder(name); else repository.renameFolder(folder.id, name);
                        runOnUiThread(this::loadNotes);
                    });
                }));
        dialog.show();
    }

    private void confirmDeleteFolder(NoteRepository.Folder folder) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_folder)
                .setMessage(R.string.delete_folder_message).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> executor.execute(() -> {
                    repository.deleteFolder(folder.id);
                    runOnUiThread(this::loadNotes);
                })).show();
    }

    private boolean handleRootDrop(DragEvent event) {
        // 仅在文件夹内部显示根级投放区；放下后以 null folderId 表示移回“未归档”。
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED:
                rootDropTarget.setAlpha(0.65f);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                rootDropTarget.setAlpha(1f);
                return true;
            case DragEvent.ACTION_DROP:
                rootDropTarget.setAlpha(1f);
                if (event.getLocalState() instanceof NoteRepository.Note) {
                    moveNote((NoteRepository.Note) event.getLocalState(), null);
                }
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                rootDropTarget.setAlpha(1f);
                rootDropTarget.setVisibility(View.GONE);
                return true;
            default:
                return true;
        }
    }

    private void moveNote(NoteRepository.Note note, Long folderId) {
        executor.execute(() -> {
            repository.moveNote(note.id, folderId);
            runOnUiThread(() -> {
                Snackbar.make(findViewById(R.id.note_root), R.string.note_moved, Snackbar.LENGTH_SHORT).show();
                loadNotes();
            });
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (swipeBack != null && swipeBack.handle(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onDestroy() {
        searchHandler.removeCallbacks(searchTask);
        CloudClient client = cloudClient;
        if (client != null) {
            client.cancel();
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    /**
     * 与 {@link ActivityResultContracts.OpenDocument} 相同，但显式申请写权限。
     *
     * <p>默认契约只保证读取权限，而「上传成功后删除手机上的原文件」需要写权限。拿不到写权限时删除会
     * 失败，此时界面提示手动清理，而不是谎报已删除。</p>
     */
    private static final class OpenDocumentWithWrite extends ActivityResultContracts.OpenDocument {
        @NonNull @Override
        public Intent createIntent(@NonNull Context context, @NonNull String[] input) {
            return super.createIntent(context, input).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
    }
}

/** 将文件夹和笔记合并为一个异构列表，并负责建立原生 Drag & Drop 手势。 */
final class NoteAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int FOLDER = 0;
    private static final int NOTE = 1;
    interface Listener {
        void onOpen(NoteRepository.Note note);
        void onOpenFolder(NoteRepository.Folder folder);
        void onFolderOptions(NoteRepository.Folder folder);
        void onNoteDragStarted(NoteRepository.Note note);
        void onDropOnFolder(NoteRepository.Note note, NoteRepository.Folder folder);
    }
    private final List<Object> items = new ArrayList<>();
    private final Listener listener;
    NoteAdapter(Listener listener) { this.listener = listener; }
    void submit(List<NoteRepository.Folder> folders, List<NoteRepository.Note> notes) {
        items.clear(); items.addAll(folders); items.addAll(notes); notifyDataSetChanged();
    }
    @Override public int getItemViewType(int position) { return items.get(position) instanceof NoteRepository.Folder ? FOLDER : NOTE; }
    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == FOLDER) return new FolderHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_note_folder, parent, false));
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_note, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder rawHolder, int position) {
        Object item = items.get(position);
        if (item instanceof NoteRepository.Folder) {
            NoteRepository.Folder folder = (NoteRepository.Folder) item;
            FolderHolder holder = (FolderHolder) rawHolder;
            holder.name.setText(folder.name);
            holder.count.setText(holder.itemView.getContext().getString(R.string.folder_count, folder.noteCount));
            holder.itemView.setOnClickListener(view -> listener.onOpenFolder(folder));
            holder.itemView.setOnLongClickListener(view -> { listener.onFolderOptions(folder); return true; });
            holder.itemView.setOnDragListener((view, event) -> {
                // 文件夹卡片是 DropTarget；描边仅表示当前拖拽已进入可投放区域。
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_ENTERED:
                        ((MaterialCardView)view).setStrokeWidth(4);
                        return true;
                    case DragEvent.ACTION_DRAG_EXITED:
                    case DragEvent.ACTION_DRAG_ENDED:
                        ((MaterialCardView)view).setStrokeWidth(0);
                        return true;
                    case DragEvent.ACTION_DROP:
                        ((MaterialCardView)view).setStrokeWidth(0);
                        if (event.getLocalState() instanceof NoteRepository.Note)
                            listener.onDropOnFolder((NoteRepository.Note) event.getLocalState(), folder);
                        return true;
                    default: return true;
                }
            });
            return;
        }
        NoteRepository.Note note = (NoteRepository.Note) item;
        Holder holder = (Holder) rawHolder;
        holder.title.setText(note.title);
        // 正文优先；改造前的老笔记文本还在备注里，回退过去显示，否则列表里会全变成"空白笔记"。
        String preview = MarkdownRenderer.plainPreview(note.body);
        if (preview.isEmpty()) {
            preview = MarkdownRenderer.plainPreview(note.content);
        }
        holder.preview.setText(preview.isEmpty() ? "空白笔记" : preview);
        holder.date.setText(new SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(new Date(note.updatedAt)));
        holder.itemView.setOnClickListener(view -> listener.onOpen(note));
        holder.itemView.setOnLongClickListener(view -> {
            listener.onNoteDragStarted(note);
            // ClipData 提供可跨边界表达的数据，localState 则在本进程内直接携带完整 Note 供目标读取。
            ClipData data = ClipData.newPlainText("note_id", String.valueOf(note.id));
            view.startDragAndDrop(data, new View.DragShadowBuilder(view), note, 0);
            return true;
        });
    }
    @Override public int getItemCount() { return items.size(); }
    static final class Holder extends RecyclerView.ViewHolder {
        final TextView title, preview, date;
        Holder(View view) { super(view); title=view.findViewById(R.id.note_item_title); preview=view.findViewById(R.id.note_item_preview); date=view.findViewById(R.id.note_item_date); }
    }
    static final class FolderHolder extends RecyclerView.ViewHolder {
        final TextView name, count;
        FolderHolder(View view) { super(view); name=view.findViewById(R.id.folder_item_name); count=view.findViewById(R.id.folder_item_count); }
    }
}
