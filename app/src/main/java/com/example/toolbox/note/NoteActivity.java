package com.example.toolbox.note;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.DragEvent;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.toolbox.R;
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
import java.io.InputStream;
import java.io.OutputStream;

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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<NoteRepository.Note> notes = new ArrayList<>();
    private final List<NoteRepository.Folder> folders = new ArrayList<>();
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onFilePicked);
    private NoteRepository repository;
    private NoteAdapter adapter;
    private View listPage, detailPage, editor, reader, empty;
    private EditText search, titleInput, contentInput;
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
        bindViews();
        configureInsets();
        configureActions();
        loadNotes();
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
        contentInput = findViewById(R.id.note_content_input);
        editorAttachments = findViewById(R.id.editor_attachments);
        readerAttachments = findViewById(R.id.reader_attachments);
        detailToolbar = findViewById(R.id.note_detail_toolbar);
        listToolbar = findViewById(R.id.note_toolbar);
        location = findViewById(R.id.note_location);
        rootDropTarget = findViewById(R.id.root_drop_target);
        detailToolbar.inflateMenu(R.menu.note_detail_toolbar);
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
        current.folderId = currentFolder == null ? null : currentFolder.id;
        showDetail();
        showEditor();
    }

    @Override public void onOpen(NoteRepository.Note note) {
        executor.execute(() -> {
            NoteRepository.Note loaded = repository.get(note.id);
            runOnUiThread(() -> { current = loaded; showDetail(); showReader(); });
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
        contentInput.setText(current.content);
        renderEditorAttachments();
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
        content.setText(MarkdownRenderer.render(current.content));
        content.setMovementMethod(LinkMovementMethod.getInstance());
        renderReaderAttachments();
    }

    private void saveNote() {
        String title = titleInput.getText().toString().trim();
        if (title.isEmpty()) {
            Snackbar.make(findViewById(R.id.note_root), R.string.note_title_required, Snackbar.LENGTH_SHORT).show();
            return;
        }
        current.title = title;
        current.content = contentInput.getText().toString();
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
        });
    }

    private void prefixLine(String prefix) {
        Editable text = contentInput.getText();
        int cursor = Math.max(0, contentInput.getSelectionStart());
        int lineStart = cursor;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        text.insert(lineStart, prefix);
        contentInput.setSelection(cursor + prefix.length());
    }

    private void wrapSelection(String before, String after, String placeholder) {
        Editable text = contentInput.getText();
        int start = Math.max(0, contentInput.getSelectionStart());
        int end = Math.max(start, contentInput.getSelectionEnd());
        String selected = start == end ? placeholder : text.subSequence(start, end).toString();
        text.replace(start, end, before + selected + after);
        contentInput.setSelection(start + before.length(), start + before.length() + selected.length());
    }

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
        executor.execute(() -> {
            try {
                File directory = new File(getFilesDir(), "note_attachments");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IllegalStateException("无法创建附件目录");
                }
                String safeName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
                File destination = new File(directory, System.currentTimeMillis() + "_" + safeName);
                long copied = 0;
                try (InputStream input = getContentResolver().openInputStream(uri);
                     OutputStream output = new FileOutputStream(destination)) {
                    if (input == null) throw new IllegalStateException("无法读取源文件");
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        copied += count;
                    }
                }
                Uri managedUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".files", destination);
                long finalSize = copied > 0 ? copied : sourceSize;
                runOnUiThread(() -> {
                    current.attachments.add(new NoteRepository.Attachment(0, managedUri.toString(),
                            fileName, mimeType, finalSize));
                    renderEditorAttachments();
                    Snackbar.make(findViewById(R.id.note_root), R.string.attachment_imported,
                            Snackbar.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> Snackbar.make(findViewById(R.id.note_root),
                        error.getMessage(), Snackbar.LENGTH_LONG).show());
            }
        });
    }

    private void renderEditorAttachments() {
        editorAttachments.removeAllViews();
        for (NoteRepository.Attachment attachment : new ArrayList<>(current.attachments)) {
            MaterialButton button = new MaterialButton(this);
            button.setText(attachment.name + "  ×");
            button.setOnClickListener(view -> {
                current.attachments.remove(attachment);
                renderEditorAttachments();
            });
            editorAttachments.addView(button);
        }
    }

    private void renderReaderAttachments() {
        readerAttachments.removeAllViews();
        for (NoteRepository.Attachment attachment : current.attachments) {
            MaterialButton button = new MaterialButton(this);
            button.setText(attachment.name);
            button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            button.setOnClickListener(view -> openAttachment(attachment));
            readerAttachments.addView(button, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        readerAttachments.setVisibility(current.attachments.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openAttachment(NoteRepository.Attachment attachment) {
        // 把持久化的 Content URI 交给系统中支持该 MIME 类型的应用，并临时转授读取权限。
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(attachment.uri));
        intent.setDataAndType(Uri.parse(attachment.uri), attachment.mimeType == null ? "*/*" : attachment.mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(intent); }
        catch (ActivityNotFoundException e) {
            Snackbar.make(findViewById(R.id.note_root), R.string.file_open_failed, Snackbar.LENGTH_LONG).show();
        }
    }

    private void confirmDelete() {
        if (current == null || current.id == 0) { showList(); return; }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.delete_note_title)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> executor.execute(() -> {
                    repository.delete(current.id);
                    runOnUiThread(() -> { showList(); loadNotes(); });
                })).show();
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
        executor.shutdownNow();
        super.onDestroy();
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
        String preview = MarkdownRenderer.plainPreview(note.content);
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
