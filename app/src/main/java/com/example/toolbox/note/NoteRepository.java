package com.example.toolbox.note;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.toolbox.repository.ToolboxDatabaseHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 笔记、文件夹及附件元数据的 SQLite 仓库。
 *
 * <p>{@link Note#folderId} 是可空关系：{@code null} 表示笔记位于“未归档”根级，而不是缺失数据。
 * 因此根级列表使用 {@code folder_id IS NULL}，移动到根级会显式写入 SQL NULL。删除文件夹也不会删除
 * 其中笔记，而是在同一事务内先把这些笔记移到根级，再删除文件夹。</p>
 *
 * <p>仓库方法本身是同步阻塞 API，不负责线程切换；Activity 应统一在后台执行器调用。事务用于保证文件夹
 * 删除以及笔记正文/附件整体保存的原子性。</p>
 */
public final class NoteRepository {
    private final ToolboxDatabaseHelper helper;

    public NoteRepository(Context context) {
        helper = new ToolboxDatabaseHelper(context);
    }

    public List<Note> list(Long folderId, String query) {
        List<Note> result = new ArrayList<>();
        // null 具有“仅查询未归档笔记”的业务含义，并非“不限制文件夹”的通配条件。
        String selection = folderId == null ? "folder_id IS NULL" : "folder_id = ?";
        List<String> arguments = new ArrayList<>();
        if (folderId != null) arguments.add(String.valueOf(folderId));
        if (query != null && !query.trim().isEmpty()) {
            selection += " AND (title LIKE ? OR content LIKE ?)";
            String value = "%" + query.trim() + "%";
            arguments.add(value);
            arguments.add(value);
        }
        try (Cursor cursor = helper.getReadableDatabase().query("note", null, selection,
                arguments.toArray(new String[0]),
                null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) result.add(readNote(cursor));
        }
        return result;
    }

    public List<Note> searchAll(String query) {
        // 全局搜索刻意忽略当前文件夹，命中结果可来自任意文件夹或未归档区域。
        List<Note> result = new ArrayList<>();
        String value = "%" + query.trim() + "%";
        try (Cursor cursor = helper.getReadableDatabase().query("note", null,
                "title LIKE ? OR content LIKE ?", new String[]{value, value},
                null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) result.add(readNote(cursor));
        }
        return result;
    }

    public List<Folder> listFolders() {
        List<Folder> result = new ArrayList<>();
        String sql = "SELECT f.folder_id,f.name,f.created_at,f.updated_at,COUNT(n.note_id) "
                + "FROM note_folder f LEFT JOIN note n ON n.folder_id=f.folder_id "
                + "GROUP BY f.folder_id ORDER BY f.updated_at DESC";
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                result.add(new Folder(cursor.getLong(0), cursor.getString(1), cursor.getLong(2),
                        cursor.getLong(3), cursor.getInt(4)));
            }
        }
        return result;
    }

    public long createFolder(String name) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("name", requireName(name));
        values.put("created_at", now);
        values.put("updated_at", now);
        return helper.getWritableDatabase().insertOrThrow("note_folder", null, values);
    }

    public void renameFolder(long folderId, String name) {
        ContentValues values = new ContentValues();
        values.put("name", requireName(name));
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update("note_folder", values, "folder_id = ?",
                new String[]{String.valueOf(folderId)});
    }

    public void deleteFolder(long folderId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            // 先解除可空关联再删除文件夹，确保任何一步失败时都由事务整体回滚。
            ContentValues values = new ContentValues();
            values.putNull("folder_id");
            values.put("updated_at", System.currentTimeMillis());
            db.update("note", values, "folder_id = ?", new String[]{String.valueOf(folderId)});
            db.delete("note_folder", "folder_id = ?", new String[]{String.valueOf(folderId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void moveNote(long noteId, Long folderId) {
        ContentValues values = new ContentValues();
        if (folderId == null) values.putNull("folder_id"); else values.put("folder_id", folderId);
        values.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update("note", values, "note_id = ?",
                new String[]{String.valueOf(noteId)});
        if (folderId != null) {
            // 文件夹列表按 updated_at 排序；移入笔记时触碰目标文件夹，使最近使用的文件夹提前。
            ContentValues touched = new ContentValues();
            touched.put("updated_at", System.currentTimeMillis());
            helper.getWritableDatabase().update("note_folder", touched, "folder_id = ?",
                    new String[]{String.valueOf(folderId)});
        }
    }

    public Note get(long noteId) {
        // 列表查询不加载附件；只有打开详情时才追加附件，减少列表滚动前的数据库工作量。
        try (Cursor cursor = helper.getReadableDatabase().query("note", null, "note_id = ?",
                new String[]{String.valueOf(noteId)}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return null;
            Note note = readNote(cursor);
            note.attachments.addAll(getAttachments(noteId));
            return note;
        }
    }

    public long save(Note note) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            ContentValues values = new ContentValues();
            values.put("title", note.title.trim());
            values.put("content", note.content == null ? "" : note.content);
            if (note.folderId == null) values.putNull("folder_id"); else values.put("folder_id", note.folderId);
            values.put("updated_at", now);
            if (note.id == 0) {
                values.put("created_at", now);
                note.id = db.insertOrThrow("note", null, values);
            } else {
                db.update("note", values, "note_id = ?", new String[]{String.valueOf(note.id)});
            }
            // 附件采用“删除旧集合后按当前内存集合重建”；与正文处于同一事务，不会保存出半套附件。
            db.delete("note_attachment", "note_id = ?", new String[]{String.valueOf(note.id)});
            for (Attachment attachment : note.attachments) {
                ContentValues item = new ContentValues();
                item.put("note_id", note.id);
                item.put("uri", attachment.uri);
                item.put("name", attachment.name);
                item.put("mime_type", attachment.mimeType);
                item.put("size", attachment.size);
                attachment.id = db.insertOrThrow("note_attachment", null, item);
            }
            db.setTransactionSuccessful();
            return note.id;
        } finally {
            db.endTransaction();
        }
    }

    public void delete(long noteId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("note_attachment", "note_id = ?", new String[]{String.valueOf(noteId)});
            db.delete("note", "note_id = ?", new String[]{String.valueOf(noteId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private List<Attachment> getAttachments(long noteId) {
        List<Attachment> result = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query("note_attachment", null,
                "note_id = ?", new String[]{String.valueOf(noteId)}, null, null, "attachment_id ASC")) {
            while (cursor.moveToNext()) {
                result.add(new Attachment(cursor.getLong(cursor.getColumnIndexOrThrow("attachment_id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("uri")),
                        cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("size"))));
            }
        }
        return result;
    }

    private static Note readNote(Cursor cursor) {
        int folderIndex = cursor.getColumnIndex("folder_id");
        return new Note(cursor.getLong(cursor.getColumnIndexOrThrow("note_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("content")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                folderIndex < 0 || cursor.isNull(folderIndex) ? null : cursor.getLong(folderIndex));
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("文件夹名称不能为空");
        return name.trim();
    }

    public static final class Note {
        public long id, createdAt, updatedAt;
        /** 所属文件夹主键；为 {@code null} 时表示位于未归档根级。 */
        public Long folderId;
        public String title, content;
        public final List<Attachment> attachments = new ArrayList<>();
        public Note() { }
        public Note(long id, String title, String content, long createdAt, long updatedAt, Long folderId) {
            this.id=id; this.title=title; this.content=content; this.createdAt=createdAt; this.updatedAt=updatedAt;
            this.folderId=folderId;
        }
    }

    public static final class Folder {
        public final long id, createdAt, updatedAt;
        public final String name;
        public final int noteCount;
        public Folder(long id, String name, long createdAt, long updatedAt, int noteCount) {
            this.id=id; this.name=name; this.createdAt=createdAt; this.updatedAt=updatedAt; this.noteCount=noteCount;
        }
    }

    public static final class Attachment {
        public long id, size;
        /** 保存的是 Content URI 及展示元数据，文件内容不复制进数据库。 */
        public final String uri, name, mimeType;
        public Attachment(long id, String uri, String name, String mimeType, long size) {
            this.id=id; this.uri=uri; this.name=name; this.mimeType=mimeType; this.size=size;
        }
    }
}
