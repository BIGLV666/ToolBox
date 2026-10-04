package com.example.toolbox.repository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.toolbox.Password.model.Password;
import com.example.toolbox.smoking.SmokingRecord;
import com.example.toolbox.util.DatabaseUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 应用内各功能模块共用的 SQLite 数据库入口。
 *
 * <p>数据库版本号必须与 {@link #onUpgrade(SQLiteDatabase, int, int)} 中的增量迁移保持同步。
 * 新安装会由 {@link #onCreate(SQLiteDatabase)} 直接得到最新结构，已安装应用则按旧版本依次补齐结构，
 * 因此新增表或字段时不能只修改建表语句。</p>
 */
public class ToolboxDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "toolbox.db";
    private static final int DB_VERSION = 13;

    public ToolboxDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 全新数据库直接创建当前版本的完整结构，无需重放历史迁移。
        db.execSQL(DatabaseUtil.createTableSql(Password.class));
        createAgentTables(db);
        createNoteTables(db);
        createSmokingTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 各版本判断相互独立，使跨多个版本升级时能够按顺序执行所有缺失的迁移。
        if (oldVersion < 2) {
            // v2 为已有密码表增加分类；默认值同时保证历史记录满足 NOT NULL 约束。
            db.execSQL("ALTER TABLE `password` ADD COLUMN `category` VARCHAR(255) NOT NULL DEFAULT '其他'");
        }
        if (oldVersion < 3) {
            // v3 引入智能体相关表及其查询索引。
            createAgentTables(db);
        }
        if (oldVersion < 4) {
            // 从 v1-v3 升级时直接创建当前完整的笔记结构，其中已经包含 v5 的文件夹字段。
            createNoteTables(db);
        }
        if (oldVersion >= 4 && oldVersion < 5) {
            // v4 已存在旧版 note 表，只能通过加列升级；限定 oldVersion >= 4 可避免与上面的最新建表重复。
            db.execSQL("CREATE TABLE IF NOT EXISTS `note_folder` ("
                    + "`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL, "
                    + "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)");
            db.execSQL("ALTER TABLE `note` ADD COLUMN `folder_id` INTEGER");
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_note_folder` ON `note` (`folder_id`, `updated_at` DESC)");
        }
        if (oldVersion >= 3 && oldVersion < 6) {
            db.execSQL("ALTER TABLE `agent_model` ADD COLUMN `protocol` TEXT NOT NULL "
                    + "DEFAULT 'openai_chat_completions'");
            db.execSQL("ALTER TABLE `agent_conversation` ADD COLUMN `summary` TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE `agent_conversation` ADD COLUMN `summarized_message_id` INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 6) createAgentSettings(db);
        if (oldVersion >= 3 && oldVersion < 7) {
            db.execSQL("ALTER TABLE `agent_message` ADD COLUMN `input_tokens` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `agent_message` ADD COLUMN `output_tokens` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `agent_message` ADD COLUMN `cached_tokens` INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion >= 6 && oldVersion < 8) {
            db.execSQL("ALTER TABLE `agent_settings` ADD COLUMN `workspace_tree_uri` TEXT");
        }
        if (oldVersion < 9) {
            // v9 引入抽烟统计表。
            createSmokingTables(db);
        }
        if (oldVersion >= 4 && oldVersion < 10) {
            // v10 让附件支持云端存储。这里限定 oldVersion >= 4：更早的版本会走上面的 createNoteTables，
            // 那里已经直接建成含新列的最新结构，重复 ALTER 会因字段已存在而失败。
            db.execSQL("ALTER TABLE `note_attachment` ADD COLUMN `local_id` TEXT");
            db.execSQL("ALTER TABLE `note_attachment` ADD COLUMN `remote_key` TEXT");
            db.execSQL("ALTER TABLE `note_attachment` ADD COLUMN `remote_bucket` TEXT");
            db.execSQL("ALTER TABLE `note_attachment` ADD COLUMN `md5` TEXT");
            db.execSQL("ALTER TABLE `note_attachment` ADD COLUMN `upload_state` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `note_attachment` ADD COLUMN `local_path` TEXT");
        }
        if (oldVersion < 10) {
            // 建表语句自带 IF NOT EXISTS，因此无论上面是否已随最新结构创建，这里再执行一次都安全。
            createNoteCloudSettings(db);
        }
        if (oldVersion >= 4 && oldVersion < 11) {
            // v11：笔记可同步（稳定标识 + 正文字段），附件可加密。限定 >= 4 的理由同 v10。
            db.execSQL("ALTER TABLE `note` ADD COLUMN `sync_id` TEXT");
            db.execSQL("ALTER TABLE `note` ADD COLUMN `body` TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE `note` ADD COLUMN `sync_state` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `note` ADD COLUMN `synced_at` INTEGER");
            db.execSQL("ALTER TABLE `note_attachment` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE `note_attachment` ADD COLUMN `plain_sha256` TEXT");
        }
        if (oldVersion >= 10 && oldVersion < 13) {
            // note_cloud_settings 是 v10 才出现的表，只有 v10 起的库才需要补列；
            // 更早的库在上面 createNoteCloudSettings() 里已经按最新结构建好。
            db.execSQL("ALTER TABLE `note_cloud_settings` ADD COLUMN `encrypted_mk` TEXT");
            db.execSQL("ALTER TABLE `note_cloud_settings` ADD COLUMN `keyring_text` TEXT");
            db.execSQL("ALTER TABLE `note_cloud_settings` ADD COLUMN `device_id` TEXT");
        }
        if (oldVersion < 13) {
            // v13 引入墓碑表：删除要在两端传播，靠的就是它 + 云端的删除标记。
            createTombstoneTable(db);
        }
        if (oldVersion < 11) {
            createCloudTrashTable(db);
            // v11 起「上传成功后删除本地文件」的默认值改为 1（只留云端）。
            // 存量配置行一并改过来，否则老用户看不到新的默认行为。
            db.execSQL("UPDATE `note_cloud_settings` SET `delete_local` = 1 WHERE `delete_local` = 0");
            backfillSyncIds(db);
            // 索引也必须在这里补：新建库走 createNoteTables 会带上它，升级库不会。
            // 放在回填之后执行，保证不会因为历史数据里出现重复值而建索引失败。
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_note_sync_id` ON `note` (`sync_id`)");
        }
        if (oldVersion >= 4 && oldVersion < 12) {
            // v12：笔记可逐篇选择是否加密上传。列默认 -1 表示"尚未决定"，首次上传时才询问。
            db.execSQL("ALTER TABLE `note` ADD COLUMN `encrypted` INTEGER NOT NULL DEFAULT -1");
        }
        if (oldVersion < 12) {
            // 迁移：改造至今云端所有内容都是加密上传的，因此把已同步过的笔记与附件标记为加密，
            // 否则界面会显示"未设定"，还会在下次上传时多问一次已经答过的问题。
            db.execSQL("UPDATE `note` SET `encrypted` = 1 WHERE `encrypted` = -1 AND ("
                    + "`synced_at` IS NOT NULL OR `note_id` IN (SELECT `note_id` FROM `note_attachment` "
                    + "WHERE `remote_key` IS NOT NULL AND `remote_key` <> ''))");
            db.execSQL("UPDATE `note_attachment` SET `encrypted` = 1 "
                    + "WHERE `remote_key` IS NOT NULL AND `remote_key` <> ''");
        }
    }

    /**
     * 给历史笔记补上跨设备标识 {@code sync_id}。
     *
     * <p>没有它就无法同步，而且必须一次性补齐——若留到运行期分批生成，同一篇笔记在不同设备上会被
     * 当成两篇，产生重复数据且难以合并。</p>
     */
    private static void backfillSyncIds(SQLiteDatabase db) {
        List<Long> pending = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(
                "SELECT `note_id` FROM `note` WHERE `sync_id` IS NULL OR `sync_id` = ''", null)) {
            while (cursor.moveToNext()) {
                pending.add(cursor.getLong(0));
            }
        }
        for (Long noteId : pending) {
            ContentValues values = new ContentValues();
            values.put("sync_id", UUID.randomUUID().toString());
            db.update("note", values, "note_id = ?", new String[]{String.valueOf(noteId)});
        }
    }

    private static void createAgentTables(SQLiteDatabase db) {
        // IF NOT EXISTS 使全新建库和版本迁移可以安全复用同一组建表逻辑。
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_key_group` ("
                + "`group_id` INTEGER PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL, "
                + "`created_at` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_api_key` ("
                + "`key_id` INTEGER PRIMARY KEY AUTOINCREMENT, `group_id` INTEGER NOT NULL, "
                + "`name` TEXT NOT NULL, `base_url` TEXT NOT NULL, `encrypted_key` TEXT NOT NULL, "
                + "`key_hint` TEXT NOT NULL, `created_at` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_model` ("
                + "`model_id` INTEGER PRIMARY KEY AUTOINCREMENT, `key_id` INTEGER NOT NULL, "
                + "`name` TEXT NOT NULL, `protocol` TEXT NOT NULL DEFAULT 'openai_chat_completions')");
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_conversation` ("
                + "`conversation_id` INTEGER PRIMARY KEY AUTOINCREMENT, `title` TEXT NOT NULL, "
                + "`model_id` INTEGER, `summary` TEXT NOT NULL DEFAULT '', "
                + "`summarized_message_id` INTEGER NOT NULL DEFAULT 0, "
                + "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_message` ("
                + "`message_id` INTEGER PRIMARY KEY AUTOINCREMENT, `conversation_id` INTEGER NOT NULL, "
                + "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `status` TEXT NOT NULL, "
                + "`tool_name` TEXT, `tool_call_id` TEXT, `input_tokens` INTEGER NOT NULL DEFAULT 0, "
                + "`output_tokens` INTEGER NOT NULL DEFAULT 0, `cached_tokens` INTEGER NOT NULL DEFAULT 0, "
                + "`created_at` INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_agent_key_group` ON `agent_api_key` (`group_id`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_agent_model_key` ON `agent_model` (`key_id`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_agent_message_conversation` "
                + "ON `agent_message` (`conversation_id`, `message_id`)");
        createAgentSettings(db);
    }

    private static void createAgentSettings(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `agent_settings` ("
                + "`settings_id` INTEGER PRIMARY KEY, `smart_search` INTEGER NOT NULL DEFAULT 0, "
                + "`default_chat_model_id` INTEGER, `default_search_model_id` INTEGER, "
                + "`default_compression_model_id` INTEGER, `memory_token_limit` INTEGER NOT NULL DEFAULT 3200, "
                + "`tinyfish_encrypted_key` TEXT, `tinyfish_key_hint` TEXT, `workspace_tree_uri` TEXT)");
        db.execSQL("INSERT OR IGNORE INTO `agent_settings` (`settings_id`) VALUES (1)");
    }

    private static void createNoteTables(SQLiteDatabase db) {
        // 此处始终描述最新笔记结构，历史数据库的差异由 onUpgrade 单独处理。
        db.execSQL("CREATE TABLE IF NOT EXISTS `note_folder` ("
                + "`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT, `name` TEXT NOT NULL, "
                + "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `note` ("
                + "`note_id` INTEGER PRIMARY KEY AUTOINCREMENT, `title` TEXT NOT NULL, "
                + "`content` TEXT NOT NULL, `folder_id` INTEGER, `created_at` INTEGER NOT NULL, "
                + "`updated_at` INTEGER NOT NULL, "
                + "`sync_id` TEXT, `body` TEXT NOT NULL DEFAULT '', "
                + "`sync_state` INTEGER NOT NULL DEFAULT 0, `synced_at` INTEGER, "
                + "`encrypted` INTEGER NOT NULL DEFAULT -1)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `note_attachment` ("
                + "`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT, `note_id` INTEGER NOT NULL, "
                + "`local_id` TEXT, `uri` TEXT NOT NULL, `name` TEXT NOT NULL, `mime_type` TEXT, "
                + "`size` INTEGER NOT NULL, "
                + "`remote_key` TEXT, `remote_bucket` TEXT, `md5` TEXT, "
                + "`upload_state` INTEGER NOT NULL DEFAULT 0, `local_path` TEXT, "
                + "`encrypted` INTEGER NOT NULL DEFAULT 0, `plain_sha256` TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_note_updated` ON `note` (`updated_at` DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_note_folder` ON `note` (`folder_id`, `updated_at` DESC)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_note_sync_id` ON `note` (`sync_id`)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_note_attachment` ON `note_attachment` (`note_id`)");
        createNoteCloudSettings(db);
        createCloudTrashTable(db);
        createTombstoneTable(db);
    }

    /**
     * v13 引入的墓碑表。
     *
     * <p>删除要在两台设备之间传播：云端 meta 对象被覆盖成带 {@code deleted} 的完整 payload，本地则留一行
     * 墓碑。它同时是两样东西：防止同步把已删笔记拉回来的依据（永久保留，只占几十字节），以及回收站页面
     * 的数据来源。</p>
     *
     * <p>{@code payload_json} 保存删除那一刻的完整正文 payload：上传墓碑如果当时失败（断网），重试时
     * 本地行已经没了，没有这份 JSON 就只能上传一个空壳，找回页面会退化成"只有名字"。</p>
     */
    private static void createTombstoneTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `note_tombstone` ("
                + "`sync_id` TEXT PRIMARY KEY NOT NULL, `title` TEXT NOT NULL DEFAULT '', "
                + "`deleted_at` INTEGER NOT NULL, `device_id` TEXT NOT NULL DEFAULT '', "
                + "`encrypted` INTEGER NOT NULL DEFAULT 1, `payload_json` TEXT NOT NULL DEFAULT '', "
                + "`uploaded` INTEGER NOT NULL DEFAULT 0)");
    }

    /**
     * v11 引入的云端待过期表。
     *
     * <p>删除笔记时，附件行的对象键必须先落到这里再删行，否则对象键就永久丢失、云端对象再也没人回收。
     * 表中的行代表"已请求过期、尚未在服务端确认成功"，由应用启动和每次删除后重试。</p>
     */
    private static void createCloudTrashTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `note_cloud_trash` ("
                + "`trash_id` INTEGER PRIMARY KEY AUTOINCREMENT, `bucket` TEXT NOT NULL, "
                + "`object_key` TEXT NOT NULL, `requested_at` INTEGER NOT NULL, "
                + "`attempts` INTEGER NOT NULL DEFAULT 0)");
    }

    /**
     * v10 引入的云同步配置：单行表，与 {@code agent_settings} 同构。
     *
     * <p>访问密钥只以密文形式存放在 {@code encrypted_ak} / {@code encrypted_sk}，明文永不落库；
     * {@code ak_hint} 仅用于界面回显「用的是哪把密钥」。</p>
     */
    private static void createNoteCloudSettings(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `note_cloud_settings` ("
                + "`settings_id` INTEGER PRIMARY KEY, `enabled` INTEGER NOT NULL DEFAULT 0, "
                + "`bucket` TEXT NOT NULL DEFAULT '', `region` TEXT NOT NULL DEFAULT 'cn-north-1', "
                + "`endpoint` TEXT NOT NULL DEFAULT 'https://s3.cn-north-1.qiniucs.com', "
                + "`prefix` TEXT NOT NULL DEFAULT 'notes/', `delete_local` INTEGER NOT NULL DEFAULT 1, "
                + "`encrypted_ak` TEXT, `encrypted_sk` TEXT, `ak_hint` TEXT, "
                + "`encrypted_mk` TEXT, `keyring_text` TEXT, `device_id` TEXT)");
        db.execSQL("INSERT OR IGNORE INTO `note_cloud_settings` (`settings_id`) VALUES (1)");
    }

    private static void createSmokingTables(SQLiteDatabase db) {
        // 单张极简表：每行一支烟，只存时间戳，日/月聚合由查询完成。
        db.execSQL("CREATE TABLE IF NOT EXISTS `smoking_record` ("
                + "`record_id` INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "`smoked_at` INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_smoking_time` ON `smoking_record` (`smoked_at` DESC)");
    }
}
