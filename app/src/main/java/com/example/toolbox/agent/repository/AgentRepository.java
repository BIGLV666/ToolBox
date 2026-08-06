package com.example.toolbox.agent.repository;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.os.Build;
import android.os.Environment;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.toolbox.agent.security.ApiKeyCipher;
import com.example.toolbox.repository.ToolboxDatabaseHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Agent 模块的数据访问边界，统一管理 API Key 配置、可用模型、会话消息和密码条目引用。
 *
 * <p>配置采用“分组、Key、模型”三级关系：分组用于组织多个服务凭据，一个 Key 归属一个分组
 * 并保存服务地址，一个 Key 可关联多个模型；模型选项通过联表查询同时取得这三层信息。</p>
 *
 * <p>API Key 以 Android Keystore 密钥加密后落库，常规配置列表只返回脱敏提示。只有实际请求、
 * 测试连接或用户明确查看时才解密。密码管理数据则有更严格边界：Agent 检索接口只返回元数据，
 * 不读取密码明文字段。</p>
 */
public final class AgentRepository {
    public static final String PROTOCOL_CHAT = "openai_chat_completions";
    public static final String PROTOCOL_RESPONSES = "openai_responses";
    private static final Pattern FROM_TABLE = Pattern.compile(
            "\\bFROM\\s+[`\"\\[]?([A-Za-z_][A-Za-z0-9_]*)", Pattern.CASE_INSENSITIVE);
    private final ToolboxDatabaseHelper helper;
    private final ApiKeyCipher cipher;
    private final Context context;

    public AgentRepository(Context context) {
        this.context = context.getApplicationContext();
        helper = new ToolboxDatabaseHelper(context);
        cipher = new ApiKeyCipher();
    }

    public long addGroup(String name) {
        ContentValues values = new ContentValues();
        values.put("name", require(name, "分组名称"));
        values.put("created_at", System.currentTimeMillis());
        return helper.getWritableDatabase().insertOrThrow("agent_key_group", null, values);
    }

    /**
     * 原子写入一个 Key 及其模型集合。
     * Key 密文、脱敏提示和模型关系位于同一事务，避免出现已保存凭据但没有可选模型的半成品配置。
     */
    public long addKey(long groupId, String name, String baseUrl, String apiKey, List<String> models) {
        return addKey(groupId, name, baseUrl, apiKey, models, PROTOCOL_CHAT);
    }

    public long addKey(long groupId, String name, String baseUrl, String apiKey,
                       List<String> models, String protocol) {
        String normalizedUrl = normalizeUrl(baseUrl);
        String secret = require(apiKey, "API Key");
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("group_id", groupId);
            values.put("name", require(name, "Key 名称"));
            values.put("base_url", normalizedUrl);
            values.put("encrypted_key", cipher.encrypt(secret));
            values.put("key_hint", hint(secret));
            values.put("created_at", System.currentTimeMillis());
            long keyId = db.insertOrThrow("agent_api_key", null, values);
            replaceModels(db, keyId, models, protocol);
            db.setTransactionSuccessful();
            return keyId;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 原子更新 Key 的归属、服务地址和模型关系。
     * 编辑时若未输入新 Key，则保留原密文；非空新值才会重新加密并更新脱敏提示。
     */
    public void updateKey(long keyId, long groupId, String name, String baseUrl,
                           String newApiKey, List<String> models) {
        updateKey(keyId, groupId, name, baseUrl, newApiKey, models, PROTOCOL_CHAT);
    }

    public void updateKey(long keyId, long groupId, String name, String baseUrl,
                          String newApiKey, List<String> models, String protocol) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("group_id", groupId);
            values.put("name", require(name, "Key 名称"));
            values.put("base_url", normalizeUrl(baseUrl));
            if (newApiKey != null && !newApiKey.trim().isEmpty()) {
                values.put("encrypted_key", cipher.encrypt(newApiKey.trim()));
                values.put("key_hint", hint(newApiKey.trim()));
            }
            db.update("agent_api_key", values, "key_id = ?", new String[]{String.valueOf(keyId)});
            replaceModels(db, keyId, models, protocol);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void deleteGroup(long groupId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try (Cursor cursor = db.query("agent_api_key", new String[]{"key_id"}, "group_id = ?",
                new String[]{String.valueOf(groupId)}, null, null, null)) {
            while (cursor.moveToNext()) {
                db.delete("agent_model", "key_id = ?", new String[]{String.valueOf(cursor.getLong(0))});
            }
            db.delete("agent_api_key", "group_id = ?", new String[]{String.valueOf(groupId)});
            db.delete("agent_key_group", "group_id = ?", new String[]{String.valueOf(groupId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void deleteKey(long keyId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("agent_model", "key_id = ?", new String[]{String.valueOf(keyId)});
        db.delete("agent_api_key", "key_id = ?", new String[]{String.valueOf(keyId)});
    }

    /**
     * 读取用于配置界面展示的分组树。
     * 返回内容包含 Key 的名称、地址、提示和模型列表，但不解密或暴露 API Key 明文。
     */
    public List<KeyGroup> getGroups() {
        List<KeyGroup> groups = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor = db.query("agent_key_group", null, null, null, null, null, "created_at ASC")) {
            while (cursor.moveToNext()) {
                KeyGroup group = new KeyGroup(cursor.getLong(cursor.getColumnIndexOrThrow("group_id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("name")));
                group.keys.addAll(getKeys(db, group.id));
                groups.add(group);
            }
        }
        return groups;
    }

    /**
     * 展开“分组、Key、模型”关系为可直接发起请求的模型选项。
     * 此处会解密 API Key，因此返回对象应只在进程内短期使用，不应写入日志或再次持久化。
     */
    public List<ModelOption> getModelOptions() {
        List<ModelOption> result = new ArrayList<>();
        String sql = "SELECT m.model_id,m.name AS model_name,k.key_id,k.name AS key_name,k.base_url,"
                + "k.encrypted_key,g.name AS group_name,m.protocol FROM agent_model m "
                + "JOIN agent_api_key k ON k.key_id=m.key_id "
                + "JOIN agent_key_group g ON g.group_id=k.group_id ORDER BY g.created_at,k.created_at,m.model_id";
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                result.add(new ModelOption(cursor.getLong(0), cursor.getString(1), cursor.getLong(2),
                        cursor.getString(3), cursor.getString(4), cipher.decrypt(cursor.getString(5)),
                        cursor.getString(6), cursor.getString(7)));
            }
        }
        return result;
    }

    /**
     * 按 Key 标识解密凭据，供测试连接或用户明确的显示、复制操作使用。
     * 调用方负责限制明文生命周期，不应将返回值传入非必要组件。
     */
    public String getApiKey(long keyId) {
        try (Cursor cursor = helper.getReadableDatabase().query("agent_api_key",
                new String[]{"encrypted_key"}, "key_id = ?", new String[]{String.valueOf(keyId)},
                null, null, null, "1")) {
            if (!cursor.moveToFirst()) throw new IllegalArgumentException("API Key 不存在");
            return cipher.decrypt(cursor.getString(0));
        }
    }

    /** 按最近更新时间倒序读取持久化会话，并附带其原始模型名称用于历史列表展示。 */
    public List<Conversation> getConversations() {
        List<Conversation> result = new ArrayList<>();
        String sql = "SELECT c.conversation_id,c.title,c.model_id,c.updated_at,m.name "
                + "FROM agent_conversation c LEFT JOIN agent_model m ON m.model_id=c.model_id "
                + "ORDER BY c.updated_at DESC";
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                result.add(new Conversation(cursor.getLong(0), cursor.getString(1),
                        cursor.isNull(2) ? 0 : cursor.getLong(2), cursor.getLong(3), cursor.getString(4)));
            }
        }
        return result;
    }

    public ConversationContext getConversationContext(long conversationId) {
        try (Cursor cursor = helper.getReadableDatabase().query("agent_conversation",
                new String[]{"summary", "summarized_message_id"}, "conversation_id = ?",
                new String[]{String.valueOf(conversationId)}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return new ConversationContext("", 0);
            return new ConversationContext(cursor.getString(0), cursor.getLong(1));
        }
    }

    public void updateConversationSummary(long conversationId, String summary, long messageId) {
        ContentValues values = new ContentValues();
        values.put("summary", summary == null ? "" : summary);
        values.put("summarized_message_id", messageId);
        helper.getWritableDatabase().update("agent_conversation", values, "conversation_id = ?",
                new String[]{String.valueOf(conversationId)});
    }

    public AgentSettings getSettings() {
        try (Cursor cursor = helper.getReadableDatabase().query("agent_settings", null,
                "settings_id = 1", null, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return new AgentSettings(false, 0, 0, 0, 3200, "", "");
            String encrypted = cursor.getString(cursor.getColumnIndexOrThrow("tinyfish_encrypted_key"));
            String tinyFishKey = encrypted == null || encrypted.isEmpty() ? "" : cipher.decrypt(encrypted);
            return new AgentSettings(cursor.getInt(cursor.getColumnIndexOrThrow("smart_search")) != 0,
                    nullableLong(cursor, "default_chat_model_id"),
                    nullableLong(cursor, "default_search_model_id"),
                    nullableLong(cursor, "default_compression_model_id"),
                    cursor.getInt(cursor.getColumnIndexOrThrow("memory_token_limit")), tinyFishKey,
                    cursor.getString(cursor.getColumnIndexOrThrow("workspace_tree_uri")));
        }
    }

    public void saveSettings(boolean smartSearch, long chatModelId, long searchModelId,
                             long compressionModelId, int tokenLimit, String tinyFishKey) {
        saveSettings(smartSearch, chatModelId, searchModelId, compressionModelId, tokenLimit,
                tinyFishKey, getSettings().workspaceTreeUri);
    }

    public void saveSettings(boolean smartSearch, long chatModelId, long searchModelId,
                             long compressionModelId, int tokenLimit, String tinyFishKey,
                             String workspaceTreeUri) {
        if (tokenLimit != 2000 && tokenLimit != 3200 && tokenLimit != 4000) {
            throw new IllegalArgumentException("记忆档位只能是 2000、3200 或 4000");
        }
        ContentValues values = new ContentValues();
        values.put("smart_search", smartSearch ? 1 : 0);
        putModelId(values, "default_chat_model_id", chatModelId);
        putModelId(values, "default_search_model_id", searchModelId);
        putModelId(values, "default_compression_model_id", compressionModelId);
        values.put("memory_token_limit", tokenLimit);
        if (tinyFishKey != null) {
            if (tinyFishKey.trim().isEmpty()) {
                values.putNull("tinyfish_encrypted_key");
                values.putNull("tinyfish_key_hint");
            } else {
                values.put("tinyfish_encrypted_key", cipher.encrypt(tinyFishKey.trim()));
                values.put("tinyfish_key_hint", hint(tinyFishKey.trim()));
            }
        }
        if (workspaceTreeUri == null || workspaceTreeUri.trim().isEmpty()) {
            values.putNull("workspace_tree_uri");
        } else {
            values.put("workspace_tree_uri", workspaceTreeUri.trim());
        }
        helper.getWritableDatabase().update("agent_settings", values, "settings_id = 1", null);
    }

    /**
     * 在公共共享存储或用户通过系统目录选择器授权的目录中执行受限文件命令。
     * 只支持相对路径，拒绝 .. 和绝对路径，无法逃逸到授权范围之外。
     */
    public JSONObject executeWorkspaceCommand(String action, String path, String content,
                                              String destination) throws Exception {
        if (hasBroadStorageAccess()) {
            return executeBroadStorageCommand(action, path, content, destination);
        }
        AgentSettings settings = getSettings();
        if (settings.workspaceTreeUri == null || settings.workspaceTreeUri.isEmpty()) {
            throw new IllegalStateException("未开启所有文件访问权限，请通过 Agent 页面进入系统设置授权");
        }
        Uri treeUri = Uri.parse(settings.workspaceTreeUri);
        Uri target = resolveDocument(treeUri, path, "create_directory".equals(action));
        switch (action) {
            case "list":
                return listDocuments(target);
            case "read":
                return readDocument(target);
            case "write":
                return writeDocument(treeUri, path, content == null ? "" : content);
            case "create_directory":
                return new JSONObject().put("success", true).put("uri", target.toString());
            case "delete":
                if (target.equals(treeUri)) throw new IllegalArgumentException("不能删除工作区根目录");
                return new JSONObject().put("success",
                        DocumentsContract.deleteDocument(context.getContentResolver(), target));
            case "move":
                Uri destinationParent = resolveDocument(treeUri, destination, false);
                Uri parent = findParent(treeUri, path);
                Uri moved = DocumentsContract.moveDocument(context.getContentResolver(), target,
                        parent, destinationParent);
                return new JSONObject().put("success", moved != null);
            default:
                throw new IllegalArgumentException("不支持的文件操作: " + action);
        }
    }

    public boolean hasBroadStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private JSONObject executeBroadStorageCommand(String action, String path, String content,
                                                  String destination) throws Exception {
        File root = Environment.getExternalStorageDirectory().getCanonicalFile();
        File target = resolveBroadFile(root, path);
        switch (action) {
            case "list":
                if (!target.isDirectory()) throw new IllegalArgumentException("不是目录: " + path);
                JSONArray items = new JSONArray();
                File[] files = target.listFiles();
                if (files != null) {
                    for (int i = 0; i < Math.min(files.length, 100); i++) {
                        File file = files[i];
                        items.put(new JSONObject().put("name", file.getName())
                                .put("directory", file.isDirectory()).put("size", file.length())
                                .put("modified", file.lastModified()));
                    }
                }
                return new JSONObject().put("success", true).put("items", items);
            case "read":
                if (!target.isFile()) throw new IllegalArgumentException("不是文件: " + path);
                StringBuilder text = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(target), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null && text.length() < 100_000) {
                        if (text.length() > 0) text.append('\n');
                        text.append(line);
                    }
                }
                return new JSONObject().put("success", true).put("content", text.toString())
                        .put("truncated", text.length() >= 100_000);
            case "write":
                File parent = target.getParentFile();
                if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                    throw new IllegalStateException("无法创建父目录");
                }
                try (OutputStream output = new FileOutputStream(target, false)) {
                    output.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
                }
                return new JSONObject().put("success", true).put("bytes", target.length());
            case "create_directory":
                return new JSONObject().put("success", target.exists() ? target.isDirectory() : target.mkdirs());
            case "delete":
                if (target.equals(root)) throw new IllegalArgumentException("不能删除共享存储根目录");
                return new JSONObject().put("success", deleteRecursively(target, root));
            case "move":
                File destinationDir = resolveBroadFile(root, destination);
                if (!destinationDir.isDirectory()) throw new IllegalArgumentException("目标不是目录");
                File moved = new File(destinationDir, target.getName()).getCanonicalFile();
                verifyInsideRoot(root, moved);
                return new JSONObject().put("success", target.renameTo(moved));
            default:
                throw new IllegalArgumentException("不支持的文件操作: " + action);
        }
    }

    private static File resolveBroadFile(File root, String path) throws Exception {
        String safe = normalizeRelativePath(path);
        if (safe.equalsIgnoreCase("Android") || safe.toLowerCase(Locale.ROOT).startsWith("android/")) {
            throw new SecurityException("禁止操作 Android 应用与系统目录");
        }
        File target = safe.isEmpty() ? root : new File(root, safe).getCanonicalFile();
        verifyInsideRoot(root, target);
        return target;
    }

    private static void verifyInsideRoot(File root, File file) throws Exception {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new SecurityException("路径超出公共共享存储");
        }
    }

    private static boolean deleteRecursively(File file, File root) throws Exception {
        verifyInsideRoot(root, file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) if (!deleteRecursively(child, root)) return false;
            }
        }
        return file.delete();
    }

    public static boolean isDestructiveWorkspaceAction(String action) {
        return "write".equals(action) || "delete".equals(action)
                || "move".equals(action) || "create_directory".equals(action);
    }

    private Uri resolveDocument(Uri treeUri, String path, boolean createDirectory) throws Exception {
        String safe = normalizeRelativePath(path);
        Uri current = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri));
        if (safe.isEmpty()) return current;
        String[] segments = safe.split("/");
        for (int i = 0; i < segments.length; i++) {
            Uri child = findChild(current, segments[i]);
            if (child == null && createDirectory) {
                child = DocumentsContract.createDocument(context.getContentResolver(), current,
                        DocumentsContract.Document.MIME_TYPE_DIR, segments[i]);
            }
            if (child == null) throw new IllegalArgumentException("文件不存在: " + path);
            current = child;
        }
        return current;
    }

    private Uri findChild(Uri parent, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent,
                DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0));
                }
            }
        }
        return null;
    }

    private JSONObject listDocuments(Uri parent) throws Exception {
        JSONArray items = new JSONArray();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent,
                DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            while (cursor != null && cursor.moveToNext() && items.length() < 100) {
                items.put(new JSONObject().put("name", cursor.getString(0))
                        .put("type", cursor.getString(1)).put("size", cursor.getLong(2))
                        .put("modified", cursor.getLong(3)));
            }
        }
        return new JSONObject().put("success", true).put("items", items);
    }

    private JSONObject readDocument(Uri target) throws Exception {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getContentResolver().openInputStream(target), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && text.length() < 100_000) {
                if (text.length() > 0) text.append('\n');
                text.append(line);
            }
        }
        return new JSONObject().put("success", true).put("content", text.toString())
                .put("truncated", text.length() >= 100_000);
    }

    private JSONObject writeDocument(Uri treeUri, String path, String content) throws Exception {
        String safe = normalizeRelativePath(path);
        int slash = safe.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : safe.substring(0, slash);
        String name = slash < 0 ? safe : safe.substring(slash + 1);
        if (name.isEmpty()) throw new IllegalArgumentException("文件名不能为空");
        Uri parent = resolveDocument(treeUri, parentPath, false);
        Uri file = findChild(parent, name);
        if (file == null) {
            file = DocumentsContract.createDocument(context.getContentResolver(), parent,
                    "text/plain", name);
        }
        try (OutputStream output = context.getContentResolver().openOutputStream(file, "wt")) {
            if (output == null) throw new IllegalStateException("无法写入文件");
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return new JSONObject().put("success", true).put("bytes",
                content.getBytes(StandardCharsets.UTF_8).length);
    }

    private Uri findParent(Uri treeUri, String path) throws Exception {
        String safe = normalizeRelativePath(path);
        int slash = safe.lastIndexOf('/');
        return resolveDocument(treeUri, slash < 0 ? "" : safe.substring(0, slash), false);
    }

    private static String normalizeRelativePath(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.contains("../") || value.equals("..") || value.contains("/../")) {
            throw new IllegalArgumentException("路径不能包含 ..");
        }
        return value;
    }

    public void deleteConversation(long conversationId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("agent_message", "conversation_id = ?", new String[]{String.valueOf(conversationId)});
            db.delete("agent_conversation", "conversation_id = ?", new String[]{String.valueOf(conversationId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 创建绑定当前模型的会话，并同时初始化创建时间和最近更新时间。 */
    public long createConversation(String title, long modelId) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("model_id", modelId);
        values.put("created_at", now);
        values.put("updated_at", now);
        return helper.getWritableDatabase().insertOrThrow("agent_conversation", null, values);
    }

    /**
     * 持久化一条会话消息并更新会话活跃时间。
     * 状态字段用于区分可恢复的完整消息；工具名称和调用标识为工具消息预留关联信息。
     */
    public long addMessage(long conversationId, String role, String content, String status,
                           String toolName, String toolCallId) {
        return addMessage(conversationId, role, content, status, toolName, toolCallId, 0, 0, 0);
    }

    public long addMessage(long conversationId, String role, String content, String status,
                           String toolName, String toolCallId, int inputTokens,
                           int outputTokens, int cachedTokens) {
        ContentValues values = new ContentValues();
        values.put("conversation_id", conversationId);
        values.put("role", role);
        values.put("content", content);
        values.put("status", status);
        values.put("tool_name", toolName);
        values.put("tool_call_id", toolCallId);
        values.put("input_tokens", inputTokens);
        values.put("output_tokens", outputTokens);
        values.put("cached_tokens", cachedTokens);
        values.put("created_at", System.currentTimeMillis());
        long id = helper.getWritableDatabase().insertOrThrow("agent_message", null, values);
        ContentValues touched = new ContentValues();
        touched.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update("agent_conversation", touched, "conversation_id = ?",
                new String[]{String.valueOf(conversationId)});
        return id;
    }

    /**
     * 获取最近的完整消息，并恢复为从旧到新的对话顺序。
     * 查询先按主键倒序截断，以确保限制作用于最新记录；返回前再反转，满足模型上下文和界面展示顺序。
     */
    public List<ChatMessage> getRecentMessages(long conversationId, int limit) {
        List<ChatMessage> reversed = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query("agent_message", null,
                "conversation_id = ? AND status = 'complete'",
                new String[]{String.valueOf(conversationId)}, null, null,
                "message_id DESC", String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                reversed.add(new ChatMessage(cursor.getLong(cursor.getColumnIndexOrThrow("message_id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("role")),
                        cursor.getString(cursor.getColumnIndexOrThrow("content")),
                        cursor.getString(cursor.getColumnIndexOrThrow("tool_name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("tool_call_id")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("input_tokens")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("output_tokens")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("cached_tokens"))));
            }
        }
        List<ChatMessage> result = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.get(i));
        return result;
    }

    /**
     * 在本机密码库中模糊检索条目引用。
     *
     * <p>查询列严格限定为标识、名称、分类、网址和备注，不包含密码字段；因此即使结果被作为
     * 工具响应发送给模型，也不会携带密码明文。通配符会先转义，用户输入按普通文本参与匹配。</p>
     */
    public List<CredentialReference> searchPasswordReferences(String query, int limit) {
        List<CredentialReference> result = new ArrayList<>();
        String value = "%" + query.replace("%", "\\%").replace("_", "\\_") + "%";
        try (Cursor cursor = helper.getReadableDatabase().query("password",
                new String[]{"password_id", "password_name", "category", "url", "notes"},
                "password_name LIKE ? ESCAPE '\\' OR notes LIKE ? ESCAPE '\\' "
                        + "OR url LIKE ? ESCAPE '\\' OR category LIKE ? ESCAPE '\\'",
                new String[]{value, value, value, value}, null, null, "password_name ASC", String.valueOf(limit))) {
            while (cursor.moveToNext()) {
                result.add(new CredentialReference(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4)));
            }
        }
        return result;
    }

    /**
     * Executes model-generated read-only SQL against an isolated in-memory copy of the note table.
     *
     * <p>The sandbox contains note data only, including a denormalized folder name. The SQL must be
     * one SELECT statement and every FROM target must be {@code note}; joins, compound queries,
     * comments and SQLite system tables are rejected. The outer query enforces the caller's row
     * limit even if the model writes a larger LIMIT in its own SQL.</p>
     */
    public List<Map<String, Object>> queryNotes(String sql, int limit) {
        String statement = validateNoteSql(sql);
        int boundedLimit = Math.max(1, Math.min(limit, 20));
        SQLiteDatabase sandbox = SQLiteDatabase.create(null);
        try {
            sandbox.execSQL("CREATE TABLE note (note_id INTEGER, title TEXT, content TEXT, "
                    + "folder_id INTEGER, folder_name TEXT, created_at INTEGER, updated_at INTEGER)");
            String sourceSql = "SELECT n.note_id,n.title,n.content,n.folder_id,f.name,n.created_at,n.updated_at "
                    + "FROM note n LEFT JOIN note_folder f ON f.folder_id=n.folder_id";
            try (Cursor source = helper.getReadableDatabase().rawQuery(sourceSql, null)) {
                while (source.moveToNext()) {
                    ContentValues values = new ContentValues();
                    values.put("note_id", source.getLong(0));
                    values.put("title", source.getString(1));
                    values.put("content", source.getString(2));
                    if (source.isNull(3)) values.putNull("folder_id"); else values.put("folder_id", source.getLong(3));
                    values.put("folder_name", source.getString(4));
                    values.put("created_at", source.getLong(5));
                    values.put("updated_at", source.getLong(6));
                    sandbox.insertOrThrow("note", null, values);
                }
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            String limited = "SELECT * FROM (" + statement + ") LIMIT ?";
            try (Cursor cursor = sandbox.rawQuery(limited, new String[]{String.valueOf(boundedLimit)})) {
                int columnCount = Math.min(cursor.getColumnCount(), 12);
                while (cursor.moveToNext()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < columnCount; i++) {
                        String name = cursor.getColumnName(i);
                        switch (cursor.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL:
                                row.put(name, null);
                                break;
                            case Cursor.FIELD_TYPE_INTEGER:
                                row.put(name, cursor.getLong(i));
                                break;
                            case Cursor.FIELD_TYPE_FLOAT:
                                row.put(name, cursor.getDouble(i));
                                break;
                            case Cursor.FIELD_TYPE_BLOB:
                                row.put(name, "[binary]");
                                break;
                            case Cursor.FIELD_TYPE_STRING:
                            default:
                                String value = cursor.getString(i);
                                row.put(name, value != null && value.length() > 4_000
                                        ? value.substring(0, 4_000) + "…" : value);
                                break;
                        }
                    }
                    rows.add(row);
                }
            }
            return rows;
        } finally {
            sandbox.close();
        }
    }

    static String validateNoteSql(String sql) {
        if (sql == null) throw new IllegalArgumentException("SQL 不能为空");
        String statement = sql.trim();
        if (statement.length() > 2_000) throw new IllegalArgumentException("SQL 过长");
        String upper = statement.toUpperCase(Locale.ROOT);
        if (!statement.matches("(?is)^SELECT\\s+.+")) throw new IllegalArgumentException("只允许 SELECT 查询");
        if (statement.contains(";") || upper.contains("--") || upper.contains("/*") || upper.contains("*/")) {
            throw new IllegalArgumentException("只允许单条无注释 SQL");
        }
        if (upper.contains(" JOIN ") || upper.contains(" UNION ") || upper.contains(" INTERSECT ")
                || upper.contains(" EXCEPT ") || upper.contains(" SQLITE_")
                || upper.contains(" PRAGMA ") || upper.contains(" ATTACH ") || upper.contains(" DETACH ")) {
            throw new IllegalArgumentException("笔记 SQL 只能查询 note 表");
        }
        if (upper.contains("LOAD_EXTENSION") || upper.contains("RANDOMBLOB") || upper.contains("ZEROBLOB")) {
            throw new IllegalArgumentException("笔记 SQL 包含不允许的函数");
        }
        Matcher matcher = FROM_TABLE.matcher(statement);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            if (!"note".equalsIgnoreCase(matcher.group(1))) {
                throw new IllegalArgumentException("笔记 SQL 只能查询 note 表");
            }
        }
        if (!found) throw new IllegalArgumentException("SQL 必须从 note 表查询");
        return statement;
    }

    private static List<ApiKeyConfig> getKeys(SQLiteDatabase db, long groupId) {
        List<ApiKeyConfig> result = new ArrayList<>();
        try (Cursor cursor = db.query("agent_api_key", null, "group_id = ?",
                new String[]{String.valueOf(groupId)}, null, null, "created_at ASC")) {
            while (cursor.moveToNext()) {
                long keyId = cursor.getLong(cursor.getColumnIndexOrThrow("key_id"));
                ApiKeyConfig key = new ApiKeyConfig(keyId, groupId,
                        cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("base_url")),
                        cursor.getString(cursor.getColumnIndexOrThrow("key_hint")));
                try (Cursor models = db.query("agent_model", new String[]{"name", "protocol"}, "key_id = ?",
                        new String[]{String.valueOf(keyId)}, null, null, "model_id ASC")) {
                    while (models.moveToNext()) {
                        key.models.add(models.getString(0));
                        if (key.protocol == null) key.protocol = models.getString(1);
                    }
                }
                result.add(key);
            }
        }
        return result;
    }

    /**
     * 用给定集合整体替换某个 Key 的模型关系。
     * 调用方在事务中执行该操作，使 Key 配置与模型集合保持一致；空集合不构成可用配置。
     */
    private static void replaceModels(SQLiteDatabase db, long keyId, List<String> models, String protocol) {
        if (models == null || models.isEmpty()) throw new IllegalArgumentException("至少添加一个模型");
        if (!PROTOCOL_CHAT.equals(protocol) && !PROTOCOL_RESPONSES.equals(protocol)) {
            throw new IllegalArgumentException("不支持的模型协议");
        }
        List<String> retained = new ArrayList<>();
        for (String model : models) {
            String name = model.trim();
            if (name.isEmpty()) continue;
            retained.add(name);
            ContentValues value = new ContentValues();
            value.put("protocol", protocol);
            int updated = db.update("agent_model", value, "key_id = ? AND name = ?",
                    new String[]{String.valueOf(keyId), name});
            if (updated == 0) {
                value.put("key_id", keyId);
                value.put("name", name);
                db.insertOrThrow("agent_model", null, value);
            }
        }
        try (Cursor cursor = db.query("agent_model", new String[]{"model_id", "name"}, "key_id = ?",
                new String[]{String.valueOf(keyId)}, null, null, null)) {
            while (cursor.moveToNext()) {
                if (!retained.contains(cursor.getString(1))) {
                    db.delete("agent_model", "model_id = ?", new String[]{String.valueOf(cursor.getLong(0))});
                }
            }
        }
    }

    private static long nullableLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? 0 : cursor.getLong(index);
    }

    private static void putModelId(ContentValues values, String column, long id) {
        if (id <= 0) values.putNull(column); else values.put(column, id);
    }

    private static String normalizeUrl(String value) {
        String url = require(value, "Base URL");
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new IllegalArgumentException("Base URL 必须以 http:// 或 https:// 开头");
        }
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (url.endsWith("/chat/completions")) url = url.substring(0, url.length() - 17);
        if (url.endsWith("/responses")) url = url.substring(0, url.length() - 10);
        return url;
    }

    private static String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + "不能为空");
        return value.trim();
    }

    private static String hint(String key) {
        return key.length() <= 8 ? "••••" : key.substring(0, 4) + "••••" + key.substring(key.length() - 4);
    }

    public static final class KeyGroup {
        public final long id; public final String name; public final List<ApiKeyConfig> keys = new ArrayList<>();
        public KeyGroup(long id, String name) { this.id = id; this.name = name; }
    }
    public static final class ApiKeyConfig {
        public final long id, groupId; public final String name, baseUrl, hint; public String protocol; public final List<String> models = new ArrayList<>();
        public ApiKeyConfig(long id, long groupId, String name, String baseUrl, String hint) {
            this.id = id; this.groupId = groupId; this.name = name; this.baseUrl = baseUrl; this.hint = hint;
        }
    }
    public static final class ModelOption {
        public final long id, keyId; public final String model, keyName, baseUrl, apiKey, groupName, protocol;
        public ModelOption(long id, String model, long keyId, String keyName, String baseUrl, String apiKey, String groupName, String protocol) {
            this.id=id; this.model=model; this.keyId=keyId; this.keyName=keyName; this.baseUrl=baseUrl; this.apiKey=apiKey; this.groupName=groupName; this.protocol=protocol;
        }
        @Override public String toString() { return groupName + " / " + keyName + " / " + model; }
    }
    public static final class ChatMessage {
        public final long id; public final String role, content, toolName, toolCallId;
        public final int inputTokens, outputTokens, cachedTokens;
        public ChatMessage(long id, String role, String content, String toolName, String toolCallId,
                           int inputTokens, int outputTokens, int cachedTokens) {
            this.id=id; this.role=role; this.content=content; this.toolName=toolName; this.toolCallId=toolCallId;
            this.inputTokens=inputTokens; this.outputTokens=outputTokens; this.cachedTokens=cachedTokens;
        }
        public ChatMessage(String role, String content, String toolName, String toolCallId) {
            this(0, role, content, toolName, toolCallId, 0, 0, 0);
        }
    }
    public static final class CredentialReference {
        public final long id; public final String name, category, url, notes;
        public CredentialReference(long id, String name, String category, String url, String notes) { this.id=id; this.name=name; this.category=category; this.url=url; this.notes=notes; }
    }
    public static final class Conversation {
        public final long id, modelId, updatedAt; public final String title, modelName;
        public Conversation(long id, String title, long modelId, long updatedAt, String modelName) {
            this.id=id; this.title=title; this.modelId=modelId; this.updatedAt=updatedAt; this.modelName=modelName;
        }
        @Override public String toString() { return title; }
    }
    public static final class ConversationContext {
        public final String summary; public final long summarizedMessageId;
        public ConversationContext(String summary, long summarizedMessageId) { this.summary=summary; this.summarizedMessageId=summarizedMessageId; }
    }
    public static final class AgentSettings {
        public final boolean smartSearch; public final long chatModelId, searchModelId, compressionModelId;
        public final int tokenLimit; public final String tinyFishKey, workspaceTreeUri;
        public AgentSettings(boolean smartSearch, long chatModelId, long searchModelId,
                             long compressionModelId, int tokenLimit, String tinyFishKey) {
            this(smartSearch, chatModelId, searchModelId, compressionModelId, tokenLimit,
                    tinyFishKey, "");
        }
        public AgentSettings(boolean smartSearch, long chatModelId, long searchModelId,
                             long compressionModelId, int tokenLimit, String tinyFishKey,
                             String workspaceTreeUri) {
            this.smartSearch=smartSearch; this.chatModelId=chatModelId; this.searchModelId=searchModelId;
            this.compressionModelId=compressionModelId; this.tokenLimit=tokenLimit; this.tinyFishKey=tinyFishKey;
            this.workspaceTreeUri=workspaceTreeUri == null ? "" : workspaceTreeUri;
        }
    }
}
