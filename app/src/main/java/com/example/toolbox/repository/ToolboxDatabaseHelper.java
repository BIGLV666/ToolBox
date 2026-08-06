package com.example.toolbox.repository;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.toolbox.Password.model.Password;
import com.example.toolbox.util.DatabaseUtil;

/**
 * 应用内各功能模块共用的 SQLite 数据库入口。
 *
 * <p>数据库版本号必须与 {@link #onUpgrade(SQLiteDatabase, int, int)} 中的增量迁移保持同步。
 * 新安装会由 {@link #onCreate(SQLiteDatabase)} 直接得到最新结构，已安装应用则按旧版本依次补齐结构，
 * 因此新增表或字段时不能只修改建表语句。</p>
 */
public class ToolboxDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "toolbox.db";
    private static final int DB_VERSION = 8;

    public ToolboxDatabaseHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 全新数据库直接创建当前版本的完整结构，无需重放历史迁移。
        db.execSQL(DatabaseUtil.createTableSql(Password.class));
        createAgentTables(db);
        createNoteTables(db);
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
                + "`content` TEXT NOT NULL, `folder_id` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `note_attachment` ("
                + "`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT, `note_id` INTEGER NOT NULL, "
                + "`uri` TEXT NOT NULL, `name` TEXT NOT NULL, `mime_type` TEXT, `size` INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_note_updated` ON `note` (`updated_at` DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_note_folder` ON `note` (`folder_id`, `updated_at` DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_note_attachment` ON `note_attachment` (`note_id`)");
    }
}
