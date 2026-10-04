package com.example.toolbox.note;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.toolbox.repository.ToolboxDatabaseHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
            values.put("body", note.body == null ? "" : note.body);
            // 稳定标识在这里兜底生成：任何写入路径都不会留下没有 sync_id 的笔记，否则它永远无法同步。
            if (note.syncId == null || note.syncId.isEmpty()) {
                note.syncId = UUID.randomUUID().toString();
            }
            values.put("sync_id", note.syncId);
            values.put("sync_state", note.syncState);
            values.put("encrypted", note.encrypted);
            if (note.folderId == null) values.putNull("folder_id"); else values.put("folder_id", note.folderId);
            values.put("updated_at", now);
            if (note.id == 0) {
                values.put("created_at", now);
                note.id = db.insertOrThrow("note", null, values);
            } else {
                db.update("note", values, "note_id = ?", new String[]{String.valueOf(note.id)});
            }
            // 回收被移除的附件：上传过、但不在这次保存的清单里的对象进入待过期队列。
            // 必须在删除旧附件行之前做，否则对象键就再也找不回来。用户在编辑器里移除又反悔的情况
            // 不会误伤——只有真正保存了才算数。
            Set<String> keptKeys = new HashSet<>();
            for (Attachment attachment : note.attachments) {
                if (attachment.remoteKey != null && !attachment.remoteKey.isEmpty()) {
                    keptKeys.add(attachment.remoteKey);
                }
            }
            List<String[]> removed = new ArrayList<>();
            try (Cursor cursor = db.query("note_attachment",
                    new String[]{"remote_bucket", "remote_key"},
                    "note_id = ? AND remote_key IS NOT NULL AND remote_key <> '' "
                            + "AND remote_bucket IS NOT NULL AND remote_bucket <> ''",
                    new String[]{String.valueOf(note.id)}, null, null, null)) {
                while (cursor.moveToNext()) {
                    String key = cursor.getString(1);
                    if (!keptKeys.contains(key)) {
                        removed.add(new String[]{cursor.getString(0), key});
                    }
                }
            }
            for (String[] item : removed) {
                db.execSQL("INSERT INTO `note_cloud_trash` (`bucket`, `object_key`, `requested_at`) VALUES (?, ?, ?)",
                        new Object[]{item[0], item[1], now});
            }
            // 附件采用“删除旧集合后按当前内存集合重建”；与正文处于同一事务，不会保存出半套附件。
            db.delete("note_attachment", "note_id = ?", new String[]{String.valueOf(note.id)});
            for (Attachment attachment : note.attachments) {
                ContentValues item = new ContentValues();
                item.put("note_id", note.id);
                item.put("local_id", attachment.localId);
                item.put("uri", attachment.uri);
                item.put("name", attachment.name);
                item.put("mime_type", attachment.mimeType);
                item.put("size", attachment.size);
                item.put("local_path", attachment.localPath);
                item.put("remote_key", attachment.remoteKey);
                item.put("remote_bucket", attachment.remoteBucket);
                item.put("md5", attachment.md5);
                item.put("upload_state", attachment.uploadState);
                item.put("encrypted", attachment.encrypted ? 1 : 0);
                item.put("plain_sha256", attachment.plainSha256);
                attachment.id = db.insertOrThrow("note_attachment", null, item);
            }
            db.setTransactionSuccessful();
            return note.id;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 删除笔记（用户主动）。
     *
     * <p>一个事务里做四件事：把云端对象键收进待过期队列（附件 7 天、正文对象 30 天——它马上会被墓碑
     * 覆盖）；删除附件行与笔记行；落一条本地墓碑。墓碑既是"同步不得复活已删笔记"的依据，也是回收站
     * 页面的记录。</p>
     *
     * <p>真正的"上传墓碑到云端"在事务外由调用方执行；断网时凭墓碑行里保存的 {@code payloadJson} 重试，
     * 否则重试时本地行已经没了，只能传一个空壳，找回页面会退化成"只有名字"。</p>
     *
     * @return 落库的墓碑；笔记从未上传过（没有 syncId）时返回 null，无需向云端传播
     */
    public Tombstone delete(long noteId, String bucket, String metaKey, String deviceId,
                            String payloadJson, boolean encrypted) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            Note target = get(noteId);
            String syncId = target == null ? null : target.syncId;
            String title = target == null ? "" : target.title;
            db.execSQL("INSERT INTO `note_cloud_trash` (`bucket`, `object_key`, `requested_at`) "
                            + "SELECT `remote_bucket`, `remote_key`, ? FROM `note_attachment` "
                            + "WHERE `note_id` = ? AND `remote_key` IS NOT NULL AND `remote_key` <> '' "
                            + "AND `remote_bucket` IS NOT NULL AND `remote_bucket` <> ''",
                    new Object[]{now, noteId});
            if (bucket != null && !bucket.isEmpty() && metaKey != null && !metaKey.isEmpty()
                    && syncId != null && !syncId.isEmpty()) {
                db.execSQL("INSERT INTO `note_cloud_trash` (`bucket`, `object_key`, `requested_at`) VALUES (?, ?, ?)",
                        new Object[]{bucket, metaKey, now});
            }
            db.delete("note_attachment", "note_id = ?", new String[]{String.valueOf(noteId)});
            db.delete("note", "note_id = ?", new String[]{String.valueOf(noteId)});
            Tombstone tombstone = null;
            if (syncId != null && !syncId.isEmpty()) {
                tombstone = insertTombstone(syncId, title, now, deviceId, payloadJson, encrypted);
            }
            db.setTransactionSuccessful();
            return tombstone;
        } finally {
            db.endTransaction();
        }
    }

    // ---------- 墓碑：删除传播与回收站 ----------

    /** 一条删除记录。既是防复活的依据，也是回收站页面的数据来源。 */
    public static final class Tombstone {
        public final String syncId, title, deviceId, payloadJson;
        public final long deletedAt;
        public final boolean encrypted, uploaded;

        Tombstone(String syncId, String title, long deletedAt, String deviceId,
                  String payloadJson, boolean encrypted, boolean uploaded) {
            this.syncId = syncId;
            this.title = title;
            this.deletedAt = deletedAt;
            this.deviceId = deviceId;
            this.payloadJson = payloadJson;
            this.encrypted = encrypted;
            this.uploaded = uploaded;
        }
    }

    private Tombstone insertTombstone(String syncId, String title, long deletedAt, String deviceId,
                                      String payloadJson, boolean encrypted) {
        ContentValues values = new ContentValues();
        values.put("sync_id", syncId);
        values.put("title", title);
        values.put("deleted_at", deletedAt);
        values.put("device_id", deviceId);
        values.put("encrypted", encrypted ? 1 : 0);
        values.put("payload_json", payloadJson == null ? "" : payloadJson);
        values.put("uploaded", 0);
        helper.getWritableDatabase().insertWithOnConflict("note_tombstone", null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
        return new Tombstone(syncId, title, deletedAt, deviceId, payloadJson, encrypted, false);
    }

    /** 查墓碑。返回 null 表示本地没有这条删除记录。 */
    public Tombstone findTombstone(String syncId) {
        if (syncId == null || syncId.isEmpty()) {
            return null;
        }
        try (Cursor cursor = helper.getReadableDatabase().query("note_tombstone", null,
                "sync_id = ?", new String[]{syncId}, null, null, null, "1")) {
            return cursor.moveToFirst() ? readTombstone(cursor) : null;
        }
    }

    /** 全部墓碑，回收站页面用。 */
    public List<Tombstone> listTombstones() {
        List<Tombstone> items = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query("note_tombstone", null,
                null, null, null, null, "deleted_at DESC")) {
            while (cursor.moveToNext()) {
                items.add(readTombstone(cursor));
            }
        }
        return items;
    }

    /** 还没成功上传到云端的墓碑，同步的推送阶段用。 */
    public List<Tombstone> unsyncedTombstones() {
        List<Tombstone> items = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query("note_tombstone", null,
                "uploaded = 0", null, null, null, "deleted_at ASC")) {
            while (cursor.moveToNext()) {
                items.add(readTombstone(cursor));
            }
        }
        return items;
    }

    public void markTombstoneUploaded(String syncId) {
        ContentValues values = new ContentValues();
        values.put("uploaded", 1);
        helper.getWritableDatabase().update("note_tombstone", values,
                "sync_id = ?", new String[]{syncId});
    }

    /** 恢复成功或彻底删除后清掉墓碑。此后这个 syncId 再出现就按普通笔记合并。 */
    public void removeTombstone(String syncId) {
        helper.getWritableDatabase().delete("note_tombstone", "sync_id = ?", new String[]{syncId});
    }

    private static Tombstone readTombstone(Cursor cursor) {
        return new Tombstone(cursor.getString(cursor.getColumnIndexOrThrow("sync_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getLong(cursor.getColumnIndexOrThrow("deleted_at")),
                textOrNull(cursor, "device_id"),
                textOrNull(cursor, "payload_json"),
                intOrZero(cursor, "encrypted") != 0,
                intOrZero(cursor, "uploaded") != 0);
    }

    /**
     * 云端墓碑落地：删除本地对应笔记（若有）并记录墓碑。
     *
     * <p>「删了又改」可复活：本地 updatedAt 比删除时间还新，说明删除之后这边又编辑过，按后写赢保留本地
     * ——此时不写墓碑，调用方应把本地版本重新上传。</p>
     *
     * @return true 表示本地发生了删除
     */
    public boolean applyCloudDeletion(String syncId, String title, long deletedAt,
                                      String payloadJson, boolean encrypted) {
        if (syncId == null || syncId.isEmpty() || findTombstone(syncId) != null) {
            return false;
        }
        Note existing = findBySyncId(syncId);
        if (existing != null && existing.updatedAt > deletedAt) {
            return false;
        }
        if (existing != null) {
            // 云端对象的过期由删除方负责，这里只删本地行，不重复登记待过期队列。
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete("note_attachment", "note_id = ?", new String[]{String.valueOf(existing.id)});
                db.delete("note", "note_id = ?", new String[]{String.valueOf(existing.id)});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        insertTombstone(syncId, title == null ? "" : title, deletedAt, "", payloadJson, encrypted);
        return true;
    }    // ---------- 云端待过期队列 ----------

    /** 待处理过期的一项：云端对象已不再需要，但还没确认服务端已受理。 */
    public static final class TrashItem {
        public final long id;
        public final String bucket, objectKey;

        TrashItem(long id, String bucket, String objectKey) {
            this.id = id;
            this.bucket = bucket;
            this.objectKey = objectKey;
        }
    }

    public List<TrashItem> pendingTrash(int limit) {
        List<TrashItem> items = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query("note_cloud_trash",
                new String[]{"trash_id", "bucket", "object_key"}, null, null, null, null,
                "trash_id ASC", String.valueOf(Math.max(1, limit)))) {
            while (cursor.moveToNext()) {
                items.add(new TrashItem(cursor.getLong(0), cursor.getString(1), cursor.getString(2)));
            }
        }
        return items;
    }

    public void clearTrash(long trashId) {
        helper.getWritableDatabase().delete("note_cloud_trash", "trash_id = ?",
                new String[]{String.valueOf(trashId)});
    }

    /** 记录一次失败尝试，便于排查反复失败的对象，同时不影响后续重试。 */
    public void bumpTrashAttempt(long trashId) {
        helper.getWritableDatabase().execSQL(
                "UPDATE `note_cloud_trash` SET `attempts` = `attempts` + 1 WHERE `trash_id` = ?",
                new Object[]{trashId});
    }

    // ---------- 正文同步 ----------

    /** 所有具备同步条件的笔记（有 sync_id），供批量上传与恢复使用。 */
    public List<Note> listSyncableNotes() {
        List<Note> result = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query("note", null,
                "sync_id IS NOT NULL AND sync_id <> ''", null, null, null, "updated_at DESC")) {
            while (cursor.moveToNext()) {
                result.add(readNote(cursor));
            }
        }
        return result;
    }

    public Note findBySyncId(String syncId) {
        if (syncId == null || syncId.isEmpty()) {
            return null;
        }
        try (Cursor cursor = helper.getReadableDatabase().query("note", null,
                "sync_id = ?", new String[]{syncId}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            Note note = readNote(cursor);
            note.attachments.addAll(getAttachments(note.id));
            return note;
        }
    }

    /** 标记某篇笔记已同步（按稳定标识定位，不受主键变化影响）。 */
    public void markNoteSynced(String syncId, long syncedAt) {
        ContentValues values = new ContentValues();
        values.put("sync_state", Note.SYNCED);
        values.put("synced_at", syncedAt);
        helper.getWritableDatabase().update("note", values, "sync_id = ?", new String[]{syncId});
    }

    public void markNoteSyncFailed(String syncId) {
        ContentValues values = new ContentValues();
        values.put("sync_state", Note.SYNC_FAILED);
        helper.getWritableDatabase().update("note", values, "sync_id = ?", new String[]{syncId});
    }

    /** 记录某篇笔记的云端加密选择。先落库再上传，避免"问过了但没记住"而反复追问。 */
    public void updateNoteEncryption(long noteId, int encryption) {
        if (noteId == 0) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("encrypted", encryption);
        helper.getWritableDatabase().update("note", values, "note_id = ?",
                new String[]{String.valueOf(noteId)});
    }

    /**
     * 用云端版本合并一篇笔记（后写赢）。
     *
     * <p>比较的是 {@code updatedAt}：云端更新才覆盖本地，否则保持本地不动。多设备同时修改同一篇时，
     * 较早的那次修改会被静默丢弃——这是选定的策略，不是缺陷。</p>
     *
     * <p><b>备注永不参与合并</b>：它不在云端数据里，任何情况下都不能被覆盖成空。</p>
     *
     * @return true 表示本地数据发生了变化
     */
    public boolean mergeFromCloud(Note incoming, Long folderId) {
        if (incoming == null || incoming.syncId == null || incoming.syncId.isEmpty()) {
            return false;
        }
        // 墓碑优先：本地已删的笔记不得被同步拉回来。"删了又改"例外——云端版本比删除时间新时，
        // 按后写赢复活，并清掉墓碑。
        Tombstone tombstone = findTombstone(incoming.syncId);
        if (tombstone != null) {
            if (incoming.updatedAt <= tombstone.deletedAt) {
                return false;
            }
            removeTombstone(incoming.syncId);
        }
        Note existing = findBySyncId(incoming.syncId);
        if (existing == null) {
            Note created = new Note();
            created.title = incoming.title;
            created.body = incoming.body;
            created.content = "";
            created.syncId = incoming.syncId;
            created.createdAt = incoming.createdAt;
            created.updatedAt = incoming.updatedAt;
            created.folderId = folderId;
            created.syncState = Note.SYNCED;
            created.syncedAt = System.currentTimeMillis();
            save(created);
            return true;
        }
        if (incoming.updatedAt <= existing.updatedAt
                && !(isBlank(existing.body) && !isBlank(incoming.body))) {
            return false;
        }
        // 冲突备份：只有本地存在"还没同步上去的修改"时，覆盖才会真的丢数据。此时把本地版另存一篇
        // 普通新笔记（它会照常上传，因此不会只活在这台设备上），再应用云端版本。
        // 本地没有未同步修改时，覆盖是无损的，不需要备份。
        if (existing.syncState != Note.SYNCED) {
            Note backup = new Note();
            backup.title = existing.title + "（冲突备份 "
                    + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date()) + "）";
            backup.body = existing.body;
            backup.content = existing.content;
            backup.folderId = existing.folderId;
            backup.createdAt = existing.createdAt;
            save(backup);
        }
        existing.title = incoming.title;
        existing.body = incoming.body;
        existing.folderId = folderId;
        existing.syncState = Note.SYNCED;
        existing.syncedAt = System.currentTimeMillis();
        save(existing);
        return true;
    }

    /**
     * 补上云端清单里、本地还没有的附件记录。
     *
     * <p>只重建附件行，不下载文件：文件按需从云端取，因此这些记录的 {@code uri} 与 {@code local_path}
     * 都为空，打开时会走"按需下载"那条路径。</p>
     *
     * @return 新增的附件条数
     */
    public int recoverAttachments(String syncId, List<Attachment> remote) {
        Note note = findBySyncId(syncId);
        if (note == null || remote == null || remote.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (Attachment candidate : remote) {
            if (candidate.remoteKey == null || candidate.remoteKey.isEmpty()) {
                continue;
            }
            boolean known = false;
            for (Attachment existing : note.attachments) {
                if (candidate.remoteKey.equals(existing.remoteKey)) {
                    known = true;
                    break;
                }
            }
            if (known) {
                continue;
            }
            note.attachments.add(candidate);
            added++;
        }
        if (added > 0) {
            // 复用整体替换策略写出附件行；正文与备注已在 note 对象里，不会被影响。
            save(note);
        }
        return added;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 按名字找文件夹；恢复时用于把云端带回来的 folderName 落到本地文件夹。 */
    public Long findFolderIdByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        try (Cursor cursor = helper.getReadableDatabase().query("note_folder",
                new String[]{"folder_id"}, "name = ?", new String[]{name.trim()},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getLong(0) : null;
        }
    }

    private static long longOrZero(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return 0L;
        }
        return cursor.getLong(index);
    }

    private List<Attachment> getAttachments(long noteId) {
        List<Attachment> result = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query("note_attachment", null,
                "note_id = ?", new String[]{String.valueOf(noteId)}, null, null, "attachment_id ASC")) {
            while (cursor.moveToNext()) {
                Attachment attachment = new Attachment(
                        textOrNull(cursor, "local_id"),
                        cursor.getLong(cursor.getColumnIndexOrThrow("attachment_id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("uri")),
                        cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("size")),
                        textOrNull(cursor, "local_path"),
                        textOrNull(cursor, "remote_key"),
                        textOrNull(cursor, "remote_bucket"),
                        textOrNull(cursor, "md5"),
                        intOrZero(cursor, "upload_state"));
                attachment.encrypted = intOrZero(cursor, "encrypted") != 0;
                attachment.plainSha256 = textOrNull(cursor, "plain_sha256");
                result.add(attachment);
            }
        }
        return result;
    }

    /**
     * 回写异步上传的结果。
     *
     * <p>按 {@code local_id} 定位而不是自增主键：{@link #save} 采用「先删后建」重建附件行，主键会变化；
     * 而且上传完成时所属笔记可能尚未保存，此时更新不会命中任何行，结果留给随后的 {@code save} 落库。
     * 两种时序都能得到正确结果，这是选择业务侧稳定标识而非数据库主键的原因。</p>
     */
    public void updateAttachmentUpload(String localId, String remoteKey, String remoteBucket,
                                       String md5, int uploadState, String localPath,
                                       boolean encrypted, String plainSha256) {
        if (localId == null || localId.isEmpty()) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("remote_key", remoteKey);
        values.put("remote_bucket", remoteBucket);
        values.put("md5", md5);
        values.put("upload_state", uploadState);
        // 上传成功后私有副本会被删除，这里同步清空路径；失败时原样保留，作为重试来源。
        values.put("local_path", localPath);
        values.put("encrypted", encrypted ? 1 : 0);
        values.put("plain_sha256", plainSha256);
        helper.getWritableDatabase().update("note_attachment", values,
                "local_id = ?", new String[]{localId});
    }

    /** 文件夹名。正文同步时按名字带走文件夹，因为 folder_id 在别的设备上没有意义。 */
    public String folderName(long folderId) {
        try (Cursor cursor = helper.getReadableDatabase().query("note_folder", new String[]{"name"},
                "folder_id = ?", new String[]{String.valueOf(folderId)}, null, null, null, "1")) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    /**
     * 所有附件记录引用过的本地位置（{@code local_path} 与 {@code uri}）。
     *
     * <p>供清理孤儿文件使用：不在这个集合里的文件说明已经没有任何记录指向它，可以安全删除。</p>
     */
    public List<String> allAttachmentReferences() {
        List<String> values = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query("note_attachment",
                new String[]{"local_path", "uri"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) {
                    values.add(cursor.getString(0));
                }
                if (!cursor.isNull(1)) {
                    values.add(cursor.getString(1));
                }
            }
        }
        return values;
    }

    private static String textOrNull(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return null;
        }
        return cursor.getString(index);
    }

    private static int intOrZero(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return 0;
        }
        return cursor.getInt(index);
    }

    private static Note readNote(Cursor cursor) {
        int folderIndex = cursor.getColumnIndex("folder_id");
        Note note = new Note(cursor.getLong(cursor.getColumnIndexOrThrow("note_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("content")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                folderIndex < 0 || cursor.isNull(folderIndex) ? null : cursor.getLong(folderIndex));
        String body = textOrNull(cursor, "body");
        note.body = body == null ? "" : body;
        note.syncId = textOrNull(cursor, "sync_id");
        note.syncState = intOrZero(cursor, "sync_state");
        note.syncedAt = longOrZero(cursor, "synced_at");
        note.encrypted = intOrDefault(cursor, "encrypted", Note.ENCRYPTION_UNSET);
        return note;
    }

    private static int intOrDefault(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return fallback;
        }
        return cursor.getInt(index);
    }

    private static String requireName(String name) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("文件夹名称不能为空");
        return name.trim();
    }

    public static final class Note {
        public static final int SYNC_PENDING = 0;
        public static final int SYNCED = 1;
        public static final int SYNC_FAILED = 2;

        public long id, createdAt, updatedAt;
        /** 所属文件夹主键；为 {@code null} 时表示位于未归档根级。 */
        public Long folderId;
        /** 标题，会随正文一起上云（加密）。 */
        public String title;
        /** 备注：只保存在本机，任何情况下都不上传。 */
        public String content;
        /** 正文：会上传到云端，加密后存储。 */
        public String body = "";
        /** 跨设备稳定标识。自增主键在不同设备上会冲突，同步必须靠它。 */
        public String syncId;
        /** 0 待同步、1 已同步、2 同步失败。 */
        public int syncState;
        public long syncedAt;
        /** 云端加密选择：-1 尚未决定、0 明文、1 加密。首次上传前会问一次，之后可在编辑器里改。 */
        public int encrypted = ENCRYPTION_UNSET;

        /** 尚未决定：首次上传时会询问。 */
        public static final int ENCRYPTION_UNSET = -1;
        /** 云端存明文：控制台能直接查看，也能用分享链接打开。 */
        public static final int ENCRYPTION_PLAIN = 0;
        /** 云端存密文：只有持有口令的人能打开。 */
        public static final int ENCRYPTION_ENCRYPTED = 1;

        public final List<Attachment> attachments = new ArrayList<>();
        public Note() { }
        public Note(long id, String title, String content, long createdAt, long updatedAt, Long folderId) {
            this.id=id; this.title=title; this.content=content; this.createdAt=createdAt; this.updatedAt=updatedAt;
            this.folderId=folderId;
        }

        /** 是否具备上传正文的条件：有稳定标识且云端已启用同步。 */
        public boolean canSync() {
            return syncId != null && !syncId.isEmpty();
        }

        /** 本篇上传时是否加密。只有明确选过"不加密"才算明文。 */
        public boolean encryptsUpload() {
            return encrypted != ENCRYPTION_PLAIN;
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

    /**
     * 附件记录。
     *
     * <p>{@link #uri} 是本机可直接打开该文件的 Content URI，{@link #remoteKey} 是云端对象键。两者可以
     * 同时存在，也可以只剩其一，具体取决于本地方案与云同步的组合：</p>
     * <ul>
     *   <li>{@code localPath} 非空且云端还没有副本 —— 待上传或上传失败，本地副本就是重试的来源。</li>
     *   <li>{@code remoteKey} 非空 —— 云端有副本，本地被删或换了设备时可按需下载。</li>
     *   <li>两者都为空 —— 改造前保存的仅本地附件，不会被自动上传，行为与改造前完全一致。</li>
     * </ul>
     *
     * <p>{@link #localId} 用于在异步上传结束后定位数据库行：{@link #save} 会重建附件行使自增主键变化，
     * 因此主键不能作为跨保存周期的关联依据。</p>
     */
    public static final class Attachment {
        /** 云端还没这份文件。历史遗留的仅本地附件也是这个状态，靠 {@code localPath} 区分。 */
        public static final int STATE_PENDING = 0;
        public static final int STATE_UPLOADED = 1;
        public static final int STATE_FAILED = 2;

        public final String localId;
        public final String name, mimeType;
        /** 可直接打开该文件的 Content URI。原始文件被删除后会被清空，因此不是不可变字段。 */
        public String uri;
        public long id, size;
        public String localPath, remoteKey, remoteBucket, md5;
        public int uploadState;
        /** 云端对象是否为密文。改造前上传的明文附件为 false，下载时不做解密。 */
        public boolean encrypted;
        /** 明文摘要。加密上传时记录，下载解密后用它复核内容是否与当初上传的一致。 */
        public String plainSha256;

        public Attachment(String localId, long id, String uri, String name, String mimeType, long size,
                          String localPath, String remoteKey, String remoteBucket, String md5,
                          int uploadState) {
            this.localId = localId == null || localId.isEmpty() ? UUID.randomUUID().toString() : localId;
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
            this.localPath = localPath;
            this.remoteKey = remoteKey;
            this.remoteBucket = remoteBucket;
            this.md5 = md5;
            this.uploadState = uploadState;
        }

        /** 只保存在本机的附件，用于未启用云同步的情况。 */
        public static Attachment local(String uri, String name, String mimeType, long size) {
            return new Attachment(null, 0, uri, name, mimeType, size,
                    null, null, null, null, STATE_PENDING);
        }

        public boolean hasRemote() {
            return remoteKey != null && !remoteKey.isEmpty();
        }

        /** 本地副本仍在、云端还没有：等待上传或上次上传失败，可以重试。 */
        public boolean isAwaitingUpload() {
            return !hasRemote() && localPath != null && !localPath.isEmpty();
        }
    }
}
