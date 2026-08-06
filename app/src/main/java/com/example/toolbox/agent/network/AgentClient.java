package com.example.toolbox.agent.network;

import android.os.Build;

import com.example.toolbox.agent.repository.AgentRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向 OpenAI 兼容服务的轻量 Agent 客户端。
 *
 * <p>服务端需要提供 {@code /chat/completions} 流式对话接口和 {@code /models} 模型列表接口，
 * 并接受 Bearer 形式的 API Key。这里依赖的是兼容协议中的公共字段，而不是某个特定厂商的
 * SDK；不同服务若省略 {@code tools}、{@code tool_calls} 或 SSE 增量字段，会被视为能力不完整。</p>
 *
 * <p>一次运行可能包含多轮“模型请求、执行本机工具、回传工具结果”。网络连接由实例级状态
 * 跟踪，因此同一个实例按单请求使用；调用 {@link #cancel()} 会同时设置取消标志并断开当前连接。</p>
 */
public final class AgentClient {
    /** 限制一次回答中的模型往返次数，防止模型持续请求工具而形成无界循环。 */
    private static final int MAX_ITERATIONS = 6;
    private static final Pattern EXPLICIT_NOTE_COUNT = Pattern.compile("(\\d+)\\s*[条篇个]");
    private static final Pattern CHINESE_EXPANDED_NOTE_COUNT = Pattern.compile("[四五六七八九十百两]+\\s*[条篇个]");
    private volatile HttpURLConnection activeConnection;
    private volatile boolean cancelled;

    /**
     * 将网络增量和工具执行状态传递给界面层的回调。
     * 回调发生在调用 {@link #run} 的线程，监听方若更新界面必须自行切换到 UI 线程。
     */
    public interface Listener {
        void onStatus(String status);
        void onText(String delta);
        void onToolStart(String callId, String name);
        void onToolEnd(String callId, String name, boolean success);
        void onPasswordMatches(List<AgentRepository.CredentialReference> matches);
        default void onUsage(int inputTokens, int outputTokens, int cachedTokens) { }
        default boolean confirmFileOperation(String action, String path, String destination,
                                             String preview) { return false; }
    }

    public static final class FileAttachment {
        public final String id;
        public final String name;
        public final String mimeType;

        public FileAttachment(String id, String name, String mimeType) {
            this.id = id;
            this.name = name;
            this.mimeType = mimeType;
        }
    }

    public interface FileReader {
        FileChunk read(String fileId, int offset, int maxChars) throws Exception;
    }

    public static final class FileChunk {
        public final String fileId;
        public final String name;
        public final String content;
        public final int offset;
        public final int totalChars;
        public final boolean hasMore;

        public FileChunk(String fileId, String name, String content, int offset, int totalChars) {
            this.fileId = fileId;
            this.name = name;
            this.content = content;
            this.offset = offset;
            this.totalChars = totalChars;
            this.hasMore = offset + content.length() < totalChars;
        }
    }

    private static String uploadedFilesPrompt(List<FileAttachment> files) {
        StringBuilder prompt = new StringBuilder(
                "用户为本轮对话上传了以下 Word 文件。需要正文或表格内容时必须调用 read_word_file，"
                        + "不得猜测文件内容。文件只在本轮请求中可用：");
        for (FileAttachment file : files) {
            prompt.append("\n- file_id=").append(file.id)
                    .append(", name=").append(file.name)
                    .append(", mime_type=").append(file.mimeType);
        }
        return prompt.toString();
    }

    /**
     * 运行一次有界 Agent 工具循环。
     *
     * <p>只把已持久化的用户和助手消息作为普通历史发送；工具调用及其结果仅在当前运行期间
     * 按 OpenAI 兼容协议追加到消息数组。模型不再返回工具调用时，返回本轮累计的助手文本。</p>
     *
     * @param model 包含服务地址、模型名和已解密 API Key 的模型选项
     * @param history 当前会话的近期完整消息
     * @param repository 本机工具读取受限数据时使用的仓储
     * @param listener 流式文本、状态和工具事件监听器
     * @return 最后一次模型响应中累计的助手文本
     * @throws Exception 网络、协议解析或工具循环失败
     */
    public String run(AgentRepository.ModelOption model, List<AgentRepository.ChatMessage> history,
                      AgentRepository repository, Listener listener) throws Exception {
        return run(model, "", history, repository, true, "",
                Collections.emptyList(), null, listener);
    }

    public String run(AgentRepository.ModelOption model, String summary,
                       List<AgentRepository.ChatMessage> history, AgentRepository repository,
                       boolean smartSearch, String tinyFishKey, Listener listener) throws Exception {
        return run(model, summary, history, repository, smartSearch, tinyFishKey,
                Collections.emptyList(), null, listener);
    }

    public String run(AgentRepository.ModelOption model, String summary,
                      List<AgentRepository.ChatMessage> history, AgentRepository repository,
                      boolean smartSearch, String tinyFishKey,
                      List<FileAttachment> files, FileReader fileReader,
                      Listener listener) throws Exception {
        if (AgentRepository.PROTOCOL_RESPONSES.equals(model.protocol)) {
            return runResponses(model, summary, history, repository, smartSearch, tinyFishKey,
                    files, fileReader, listener);
        }
        cancelled = false;
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
                "你是 ToolBox 内置 Agent。回答简洁、准确。需要本机信息时优先使用工具。"
                        + "当用户要求查找、搜索或确认已保存的密码条目时，必须调用 search_passwords。"
                        + "search_passwords 只能检索条目元数据，绝不能声称已读取密码明文。"
                        + "当用户要求查找、回顾或整理自己的笔记时，必须调用 query_notes。"
                        + "query_notes 的 SQL 只能读取 note 表。默认 limit 为 3；"
                        + "只有用户明确要求更多条、指定数量或要求全部时，才可以传入大于 3 的 limit。"
                        + "处理公共文件时直接调用 workspace_shell。若权限不足，只提示用户开启所有文件访问，"
                        + "不要要求用户选择 SAF 文件工作区。"
                        + (summary == null || summary.isEmpty() ? "" : "\n较早会话摘要：\n" + summary)
                        + (smartSearch && tinyFishKey != null && !tinyFishKey.isEmpty()
                         ? "\n需要联网信息时调用 network_search，不要凭记忆假装已经搜索。" : "")));
        if (files != null && !files.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content",
                    uploadedFilesPrompt(files)));
        }
        for (AgentRepository.ChatMessage message : history) {
            if ("user".equals(message.role) || "assistant".equals(message.role)) {
                messages.put(new JSONObject().put("role", message.role).put("content", message.content));
            }
        }
        boolean expandedNoteResults = allowsExpandedNoteResults(history);

        // 每次工具执行结果都会回送模型，但总往返次数受限，避免错误模型无限重复调用工具。
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            listener.onStatus(iteration == 0 ? "正在连接模型" : "正在整理工具结果");
            Turn turn = request(model, messages, listener, true,
                    smartSearch ? tinyFishKey : "", files != null && !files.isEmpty());
            if (turn.toolCalls.isEmpty()) {
                return turn.content;
            }

            // 工具结果之前必须先补入带 tool_calls 的 assistant 消息，保持兼容协议的消息配对顺序。
            JSONObject assistant = new JSONObject().put("role", "assistant")
                    .put("content", turn.content.isEmpty() ? JSONObject.NULL : turn.content);
            JSONArray calls = new JSONArray();
            for (ToolCall call : turn.toolCalls) {
                calls.put(new JSONObject().put("id", call.id).put("type", "function")
                        .put("function", new JSONObject().put("name", call.name)
                                .put("arguments", call.arguments)));
            }
            assistant.put("tool_calls", calls);
            messages.put(assistant);

            for (ToolCall call : turn.toolCalls) {
                listener.onToolStart(call.id, call.name);
                String result;
                boolean success = true;
                try {
                    result = executeTool(call, repository, listener, expandedNoteResults, tinyFishKey,
                            files, fileReader).toString();
                } catch (Exception e) {
                    success = false;
                    result = new JSONObject().put("success", false)
                            .put("error", e.getMessage() == null ? "工具执行失败" : e.getMessage()).toString();
                }
                listener.onToolEnd(call.id, call.name, success);
                messages.put(new JSONObject().put("role", "tool").put("tool_call_id", call.id)
                        .put("content", result));
            }
        }
        throw new IllegalStateException("工具调用次数超过限制");
    }

    public String summarize(AgentRepository.ModelOption model, String previousSummary,
                            List<AgentRepository.ChatMessage> messages, int targetTokens) throws Exception {
        cancelled = false;
        JSONArray input = new JSONArray();
        input.put(new JSONObject().put("role", "system").put("content",
                "将会话压缩为约 " + targetTokens + " tokens 的中文记忆。保留目标、约束、关键事实、"
                        + "重要名称、待办和未解决问题，不得编造。只输出摘要正文。"
                        + (previousSummary == null || previousSummary.isEmpty() ? "" : "\n已有摘要：\n" + previousSummary)));
        for (AgentRepository.ChatMessage message : messages) {
            input.put(new JSONObject().put("role", message.role).put("content", message.content));
        }
        if (AgentRepository.PROTOCOL_RESPONSES.equals(model.protocol)) {
            return requestResponses(model, input, new NoOpListener(), false, "", false, null, false).text;
        }
        return request(model, input, new NoOpListener(), false, "", false).content;
    }

    public static int estimateTokens(String text) {
        if (text == null) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) cjk++; else other++;
        }
        return cjk + (other + 3) / 4;
    }

    /**
     * 请求取消当前网络操作。
     * 设置标志用于终止 SSE 读取循环，主动断开连接则用于唤醒可能阻塞在网络读取中的线程。
     */
    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }

    /**
     * 通过 OpenAI 兼容的 {@code GET /models} 接口验证连接并读取模型标识。
     * 该方法只验证鉴权和模型列表能力，不发起对话，也不会持久化传入的 Key。
     */
    public List<String> fetchModels(String baseUrl, String apiKey) throws Exception {
        cancelled = false;
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/models").openConnection();
        activeConnection = connection;
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("连接失败 HTTP " + code + ": " + readError(connection));
            StringBuilder json = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
            }
            JSONArray data = new JSONObject(json.toString()).optJSONArray("data");
            List<String> models = new ArrayList<>();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    String id = data.getJSONObject(i).optString("id", "").trim();
                    if (!id.isEmpty() && !models.contains(id)) models.add(id);
                }
            }
            return models;
        } finally {
            connection.disconnect();
            activeConnection = null;
        }
    }

    /**
     * 发起一次流式对话请求并将 SSE 增量还原为完整文本和工具调用。
     *
     * <p>响应按行读取，只消费 {@code data:} 事件并以 {@code [DONE]} 结束。文本增量立即通知
     * 界面；工具调用则按协议中的 {@code index} 聚合，因为调用编号、函数名和 JSON 参数可能
     * 被拆散到多个事件中。尤其是 {@code function.arguments} 必须按到达顺序原样拼接，待流结束
     * 后才能作为完整 JSON 解析，不能逐片段解析或自行插入分隔符。</p>
     */
    private Turn request(AgentRepository.ModelOption model, JSONArray messages, Listener listener,
                         boolean enableTools, String tinyFishKey, boolean includeFiles)
            throws IOException, JSONException {
        JSONObject body = new JSONObject().put("model", model.model).put("stream", true)
                .put("messages", messages);
        body.put("stream_options", new JSONObject().put("include_usage", true));
        if (enableTools) body.put("tools", toolDefinitions(
                tinyFishKey != null && !tinyFishKey.isEmpty(), includeFiles));
        HttpURLConnection connection = (HttpURLConnection) new URL(
                model.baseUrl + "/chat/completions").openConnection();
        activeConnection = connection;
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + model.apiKey);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(requestBytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(requestBytes);
        }
        Turn turn = new Turn();
        Map<Integer, ToolCall> toolCalls = new LinkedHashMap<>();
        try {
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                String detail = readError(connection);
                if (responseCode == 400 && (detail.contains("tool") || detail.contains("function"))) {
                    throw new IOException("当前模型或服务不支持 Tool Calling，无法使用本机工具");
                }
                throw new IOException("模型请求失败 HTTP " + responseCode
                        + (detail.isEmpty() ? "" : ": " + detail));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled) throw new IOException("Canceled");
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    if (data.isEmpty()) continue;
                    JSONObject event = new JSONObject(data);
                    JSONObject usage = event.optJSONObject("usage");
                    if (usage != null) notifyChatUsage(usage, listener);
                    JSONArray choices = event.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    String content = delta.optString("content", "");
                    if (!content.isEmpty()) {
                        turn.content += content;
                        listener.onText(content);
                    }
                    // 同一工具调用的参数常被分割在多个 SSE delta 中，按 index 定位并连续拼接。
                    JSONArray fragments = delta.optJSONArray("tool_calls");
                    if (fragments != null) {
                        for (int i = 0; i < fragments.length(); i++) {
                            JSONObject fragment = fragments.getJSONObject(i);
                            int index = fragment.optInt("index", i);
                            ToolCall call = toolCalls.computeIfAbsent(index, unused -> new ToolCall());
                            String id = fragment.optString("id", "");
                            if (!id.isEmpty()) call.id = id;
                            JSONObject function = fragment.optJSONObject("function");
                            if (function != null) {
                                String name = function.optString("name", "");
                                if (!name.isEmpty()) call.name += name;
                                call.arguments += function.optString("arguments", "");
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect();
            activeConnection = null;
        }
        for (ToolCall call : toolCalls.values()) {
            if (call.id.isEmpty()) call.id = "call_" + System.nanoTime();
            turn.toolCalls.add(call);
        }
        return turn;
    }

    private String runResponses(AgentRepository.ModelOption model, String summary,
                                 List<AgentRepository.ChatMessage> history, AgentRepository repository,
                                 boolean smartSearch, String tinyFishKey,
                                 List<FileAttachment> files, FileReader fileReader,
                                 Listener listener) throws Exception {
        cancelled = false;
        JSONArray input = new JSONArray();
        input.put(new JSONObject().put("role", "developer").put("content",
                "你是 ToolBox 内置 Agent。回答简洁准确。读取本机笔记时调用 query_notes。"
                        + "处理公共文件时直接调用 workspace_shell。若权限不足，只提示用户开启所有文件访问，"
                        + "不要要求用户选择 SAF 文件工作区。"
                        + (summary == null || summary.isEmpty() ? "" : "\n较早会话摘要：\n" + summary)
                        + (smartSearch && tinyFishKey != null && !tinyFishKey.isEmpty()
                         ? "\n需要联网信息时调用 network_search。" : "")));
        if (files != null && !files.isEmpty()) {
            input.put(new JSONObject().put("role", "developer").put("content",
                    uploadedFilesPrompt(files)));
        }
        for (AgentRepository.ChatMessage message : history) {
            if ("user".equals(message.role) || "assistant".equals(message.role)) {
                input.put(new JSONObject().put("role", message.role).put("content", message.content));
            }
        }
        String previousResponseId = null;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            ResponsesTurn turn = requestResponses(model, input, listener,
                    true, smartSearch ? tinyFishKey : "",
                    smartSearch && (tinyFishKey == null || tinyFishKey.isEmpty()),
                    previousResponseId, files != null && !files.isEmpty());
            if (turn.calls.isEmpty()) return turn.text;
            previousResponseId = turn.responseId;
            JSONArray outputs = new JSONArray();
            for (ResponseCall call : turn.calls) {
                listener.onToolStart(call.callId, call.name);
                JSONObject result = executeResponseTool(call, repository, tinyFishKey, listener,
                        files, fileReader);
                listener.onToolEnd(call.callId, call.name, result.optBoolean("success"));
                outputs.put(new JSONObject().put("type", "function_call_output")
                        .put("call_id", call.callId).put("output", result.toString()));
            }
            input = outputs;
        }
        throw new IllegalStateException("工具调用次数超过限制");
    }

    private ResponsesTurn requestResponses(AgentRepository.ModelOption model, JSONArray input,
                                            Listener listener, boolean enableLocalTools, String tinyFishKey,
                                            boolean webSearch,
                                            String previousResponseId, boolean includeFiles) throws Exception {
        JSONObject body = new JSONObject().put("model", model.model).put("input", input).put("stream", true);
        if (previousResponseId != null && !previousResponseId.isEmpty()) {
            body.put("previous_response_id", previousResponseId);
        }
        JSONArray tools = new JSONArray();
        if (enableLocalTools) {
            tools.put(responseNoteTool());
            tools.put(responseWorkspaceTool());
            if (tinyFishKey != null && !tinyFishKey.isEmpty()) tools.put(responseSearchTool());
            if (includeFiles) tools.put(responseWordTool());
        }
        if (webSearch) tools.put(new JSONObject().put("type", "web_search"));
        if (tools.length() > 0) body.put("tools", tools);
        HttpURLConnection connection = openPost(model.baseUrl + "/responses", model.apiKey, body);
        activeConnection = connection;
        ResponsesTurn turn = new ResponsesTurn();
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Responses API HTTP " + status + ": " + readError(connection));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled) throw new IOException("Canceled");
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JSONObject event = new JSONObject(data);
                    String type = event.optString("type");
                    if ("response.output_text.delta".equals(type)) {
                        String delta = event.optString("delta");
                        turn.text += delta;
                        listener.onText(delta);
                    } else if ("response.web_search_call.searching".equals(type)) {
                        listener.onStatus("正在使用 OpenAI 网络搜索");
                    } else if ("response.output_item.done".equals(type)) {
                        JSONObject item = event.optJSONObject("item");
                        if (item != null && "function_call".equals(item.optString("type"))) {
                            turn.calls.add(new ResponseCall(item.optString("call_id"), item.optString("name"),
                                    item.optString("arguments", "{}")));
                        }
                    } else if ("error".equals(type)) {
                        throw new IOException(event.optString("message", "Responses API 发生错误"));
                    } else if ("response.failed".equals(type)) {
                        throw new IOException("Responses API 生成失败");
                    } else if ("response.completed".equals(type)) {
                        JSONObject response = event.optJSONObject("response");
                        if (response != null) {
                            turn.responseId = response.optString("id", "");
                            JSONObject usage = response.optJSONObject("usage");
                            if (usage != null) notifyResponsesUsage(usage, listener);
                        }
                    }
                }
            }
        } finally {
            connection.disconnect();
            activeConnection = null;
        }
        return turn;
    }

    private static String tinyFishSearch(String query, String domainType, String apiKey, Listener listener) {
        if (query == null || query.trim().isEmpty()) return "";
        listener.onStatus("正在使用 TinyFish 搜索");
        HttpURLConnection connection = null;
        try {
            String address = "https://api.search.tinyfish.ai?query="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8.name())
                    + "&language=zh&location=CN&domain_type="
                    + URLEncoder.encode(domainType, StandardCharsets.UTF_8.name());
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("X-API-Key", apiKey);
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) return "";
            JSONArray results = new JSONObject(readAll(connection)).optJSONArray("results");
            if (results == null) return "";
            StringBuilder context = new StringBuilder();
            for (int i = 0; i < Math.min(8, results.length()); i++) {
                JSONObject item = results.getJSONObject(i);
                context.append(i + 1).append(". ").append(item.optString("title"))
                        .append("\n").append(item.optString("snippet"))
                        .append("\n").append(item.optString("url")).append("\n");
            }
            return context.toString();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String lastUserMessage(List<AgentRepository.ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role)) return history.get(i).content;
        }
        return "";
    }

    private static HttpURLConnection openPost(String address, String apiKey, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        return connection;
    }

    private static String readAll(HttpURLConnection connection) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static void notifyChatUsage(JSONObject usage, Listener listener) {
        int input = usage.optInt("prompt_tokens", 0);
        int output = usage.optInt("completion_tokens", 0);
        JSONObject details = usage.optJSONObject("prompt_tokens_details");
        int cached = details == null ? 0 : details.optInt("cached_tokens", 0);
        listener.onUsage(input, output, cached);
    }

    private static void notifyResponsesUsage(JSONObject usage, Listener listener) {
        int input = usage.optInt("input_tokens", 0);
        int output = usage.optInt("output_tokens", 0);
        JSONObject details = usage.optJSONObject("input_tokens_details");
        int cached = details == null ? 0 : details.optInt("cached_tokens", 0);
        listener.onUsage(input, output, cached);
    }

    private static JSONObject responseNoteTool() throws JSONException {
        JSONObject parameters = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("sql", new JSONObject().put("type", "string"))
                        .put("limit", new JSONObject().put("type", "integer")))
                .put("required", new JSONArray().put("sql").put("limit"))
                .put("additionalProperties", false);
        return new JSONObject().put("type", "function").put("name", "query_notes")
                .put("description", "用只读 SQL 查询用户本机 note 表")
                .put("parameters", parameters).put("strict", true);
    }

    private static JSONObject responseSearchTool() throws JSONException {
        JSONObject parameters = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("query", new JSONObject().put("type", "string")
                                .put("description", "需要联网搜索的查询词"))
                        .put("domain_type", new JSONObject().put("type", "string")
                                .put("enum", new JSONArray().put("web").put("news").put("research_paper"))
                                .put("description", "网页、新闻或论文")))
                .put("required", new JSONArray().put("query").put("domain_type"))
                .put("additionalProperties", false);
        return new JSONObject().put("type", "function").put("name", "network_search")
                .put("description", "使用 TinyFish 搜索实时网页、新闻或学术论文")
                .put("parameters", parameters).put("strict", true);
    }

    private static JSONObject responseWorkspaceTool() throws JSONException {
        return new JSONObject().put("type", "function").put("name", "workspace_shell")
                .put("description", "在用户授权的公共目录中列出、读取、写入、移动或删除文件")
                .put("parameters", workspaceParameters()).put("strict", true);
    }

    private static JSONObject responseWordTool() throws JSONException {
        JSONObject parameters = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("file_id", new JSONObject().put("type", "string"))
                        .put("offset", new JSONObject().put("type", "integer").put("minimum", 0))
                        .put("max_chars", new JSONObject().put("type", "integer").put("minimum", 100)
                                .put("maximum", 20000)))
                .put("required", new JSONArray().put("file_id").put("offset").put("max_chars"))
                .put("additionalProperties", false);
        return new JSONObject().put("type", "function").put("name", "read_word_file")
                .put("description", "分段读取用户本轮上传的 .docx Word 文件正文和表格文字")
                .put("parameters", parameters).put("strict", true);
    }

    private JSONObject executeResponseTool(ResponseCall call, AgentRepository repository,
                                           String tinyFishKey, Listener listener,
                                           List<FileAttachment> files, FileReader fileReader)
            throws JSONException {
        JSONObject args = new JSONObject(call.arguments);
        if ("network_search".equals(call.name)) {
            String result = tinyFishSearch(args.optString("query"),
                    args.optString("domain_type", "web"), tinyFishKey, listener);
            return new JSONObject().put("success", !result.isEmpty()).put("results", result);
        }
        if ("workspace_shell".equals(call.name)) {
            return executeWorkspace(args, repository, listener);
        }
        if ("read_word_file".equals(call.name)) {
            return executeWordTool(args, files, fileReader);
        }
        if (!"query_notes".equals(call.name)) return new JSONObject().put("success", false).put("error", "未知工具");
        List<Map<String, Object>> rows = repository.queryNotes(args.optString("sql"), args.optInt("limit", 3));
        JSONArray resultRows = new JSONArray();
        for (Map<String, Object> item : rows) {
            JSONObject row = new JSONObject();
            for (Map.Entry<String, Object> entry : item.entrySet()) {
                row.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
            }
            resultRows.put(row);
        }
        return new JSONObject().put("success", true).put("rows", resultRows);
    }

    private static String readError(HttpURLConnection connection) {
        if (connection.getErrorStream() == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && result.length() < 300) result.append(line);
        } catch (IOException ignored) {
            return "";
        }
        return result.length() > 300 ? result.substring(0, 300) : result.toString();
    }

    /**
     * 执行白名单内的本机工具。
     * 密码检索只读取条目标识、名称、分类、网址和备注等元数据；密码字段不进入结果 JSON，
     * 因而不会通过工具结果发送给模型。用户后续查看明文属于界面层单独触发的本地操作。
     */
    private static JSONObject executeTool(ToolCall call, AgentRepository repository, Listener listener,
                                          boolean expandedNoteResults, String tinyFishKey,
                                          List<FileAttachment> files, FileReader fileReader) throws JSONException {
        JSONObject arguments = call.arguments.trim().isEmpty() ? new JSONObject() : new JSONObject(call.arguments);
        JSONObject result = new JSONObject().put("success", true);
        switch (call.name) {
            case "get_current_time":
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault());
                format.setTimeZone(TimeZone.getDefault());
                return result.put("time", format.format(new Date()));
            case "get_device_info":
                return result.put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                        .put("android_version", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT);
            case "list_toolbox_modules":
                return result.put("modules", new JSONArray().put("密码管理").put("Agent").put("笔记"));
            case "search_passwords":
                String query = arguments.optString("query", "").trim();
                if (query.isEmpty()) throw new IllegalArgumentException("检索词不能为空");
                List<AgentRepository.CredentialReference> references = repository.searchPasswordReferences(query, 8);
                listener.onPasswordMatches(references);
                JSONArray matches = new JSONArray();
                for (AgentRepository.CredentialReference item : references) {
                    matches.put(new JSONObject().put("id", item.id).put("name", item.name)
                            .put("category", item.category).put("url", item.url).put("notes", item.notes));
                }
                return result.put("matches", matches)
                        .put("privacy", "未读取或发送密码明文");
            case "query_notes":
                String sql = arguments.optString("sql", "").trim();
                int limit = arguments.has("limit") ? arguments.optInt("limit", 3) : 3;
                limit = expandedNoteResults ? Math.max(1, Math.min(limit, 20)) : 3;
                List<Map<String, Object>> rows = repository.queryNotes(sql, limit);
                JSONArray noteRows = new JSONArray();
                for (Map<String, Object> item : rows) {
                    JSONObject row = new JSONObject();
                    for (Map.Entry<String, Object> entry : item.entrySet()) {
                        row.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
                    }
                    noteRows.put(row);
                }
                return result.put("rows", noteRows).put("returned", rows.size())
                        .put("limit", limit)
                        .put("schema", "note(note_id,title,content,folder_id,folder_name,created_at,updated_at)");
            case "read_word_file":
                return executeWordTool(arguments, files, fileReader);
            case "network_search":
                String webQuery = arguments.optString("query", "").trim();
                if (webQuery.isEmpty()) throw new IllegalArgumentException("搜索词不能为空");
                String searchResult = tinyFishSearch(webQuery,
                        arguments.optString("domain_type", "web"), tinyFishKey, listener);
                return result.put("results", searchResult).put("provider", "TinyFish");
            case "workspace_shell":
                return executeWorkspace(arguments, repository, listener);
            default:
                throw new IllegalArgumentException("未知工具: " + call.name);
        }
    }

    private static JSONObject executeWordTool(JSONObject arguments, List<FileAttachment> files,
                                              FileReader fileReader) throws JSONException {
        if (fileReader == null || files == null || files.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "本轮没有上传 Word 文件");
        }
        String fileId = arguments.optString("file_id", "").trim();
        int offset = Math.max(0, arguments.optInt("offset", 0));
        int maxChars = Math.max(100, Math.min(arguments.optInt("max_chars", 12000), 20000));
        FileAttachment match = null;
        for (FileAttachment file : files) {
            if (file.id.equals(fileId)) {
                match = file;
                break;
            }
        }
        if (match == null) return new JSONObject().put("success", false).put("error", "文件 ID 不存在");
        try {
            FileChunk chunk = fileReader.read(fileId, offset, maxChars);
            return new JSONObject().put("success", true).put("file_id", chunk.fileId)
                    .put("name", chunk.name).put("offset", chunk.offset)
                    .put("total_chars", chunk.totalChars).put("has_more", chunk.hasMore)
                    .put("content", chunk.content);
        } catch (Exception error) {
            return new JSONObject().put("success", false)
                    .put("error", error.getMessage() == null ? "Word 文件解析失败" : error.getMessage());
        }
    }

    static boolean allowsExpandedNoteResults(List<AgentRepository.ChatMessage> history) {
        String prompt = "";
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("user".equals(history.get(i).role)) {
                prompt = history.get(i).content == null ? "" : history.get(i).content;
                break;
            }
        }
        if (prompt.contains("更多") || prompt.contains("全部") || prompt.contains("所有")
                || prompt.contains("尽可能多") || prompt.contains("多给") || prompt.contains("不限")) {
            return true;
        }
        if (CHINESE_EXPANDED_NOTE_COUNT.matcher(prompt).find()) return true;
        Matcher matcher = EXPLICIT_NOTE_COUNT.matcher(prompt);
        while (matcher.find()) {
            try {
                if (Integer.parseInt(matcher.group(1)) > 3) return true;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }

    /**
     * 构造 OpenAI 兼容的函数工具定义。参数约束使用 JSON Schema 子集，供模型决定函数名并
     * 生成参数；声明本身不授予额外数据访问权限，实际能力仍由 {@link #executeTool} 的白名单限定。
     */
    private static JSONArray toolDefinitions(boolean includeNetworkSearch, boolean includeFiles) throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("get_current_time", "获取手机当前日期、时间和时区", new JSONObject()));
        tools.put(tool("get_device_info", "获取当前手机型号和 Android 版本", new JSONObject()));
        tools.put(tool("list_toolbox_modules", "列出 ToolBox 当前可用模块", new JSONObject()));
        JSONObject search = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("query",
                        new JSONObject().put("type", "string").put("description", "名称或备注检索词")))
                .put("required", new JSONArray().put("query"));
        tools.put(tool("search_passwords", "按名称和备注检索密码条目元数据，不返回密码明文", search));
        JSONObject noteProperties = new JSONObject()
                .put("sql", new JSONObject().put("type", "string")
                        .put("description", "只查询 note 表的单条 SELECT。可用列：note_id,title,content,"
                                + "folder_id,folder_name,created_at,updated_at。禁止 JOIN、UNION、子表和系统表。"))
                .put("limit", new JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 20)
                        .put("description", "默认 3；仅当用户明确要求更多、指定数量或全部时才传入大于 3 的值"));
        JSONObject noteQuery = new JSONObject().put("type", "object")
                .put("properties", noteProperties)
                .put("required", new JSONArray().put("sql"));
        tools.put(tool("query_notes", "用只读 SQL 查询用户本机笔记。默认最多返回 3 行", noteQuery));
        if (includeFiles) {
            JSONObject fileProperties = new JSONObject()
                    .put("file_id", new JSONObject().put("type", "string").put("description", "本轮上传文件的 ID"))
                    .put("offset", new JSONObject().put("type", "integer").put("minimum", 0)
                            .put("description", "从正文的字符偏移开始读取"))
                    .put("max_chars", new JSONObject().put("type", "integer").put("minimum", 100).put("maximum", 20000)
                            .put("description", "本次读取的最大字符数，建议 12000"));
            JSONObject fileTool = new JSONObject().put("type", "object")
                    .put("properties", fileProperties)
                    .put("required", new JSONArray().put("file_id").put("offset").put("max_chars"));
            tools.put(tool("read_word_file", "分段读取本轮上传的 .docx Word 文件正文和表格文字", fileTool));
        }
        if (includeNetworkSearch) {
            JSONObject networkProperties = new JSONObject()
                    .put("query", new JSONObject().put("type", "string").put("description", "联网搜索词"))
                    .put("domain_type", new JSONObject().put("type", "string")
                            .put("enum", new JSONArray().put("web").put("news").put("research_paper")));
            JSONObject network = new JSONObject().put("type", "object")
                    .put("properties", networkProperties)
                    .put("required", new JSONArray().put("query").put("domain_type"));
            tools.put(tool("network_search", "使用 TinyFish 搜索实时网页、新闻或论文", network));
        }
        tools.put(tool("workspace_shell", "在用户授权的公共目录内执行受限文件操作",
                workspaceParameters()));
        return tools;
    }

    private static JSONObject workspaceParameters() throws JSONException {
        return new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("action", new JSONObject().put("type", "string")
                                .put("enum", new JSONArray().put("list").put("read").put("write")
                                        .put("create_directory").put("move").put("delete")))
                        .put("path", new JSONObject().put("type", "string")
                                .put("description", "相对于授权工作区的路径，根目录用空字符串"))
                        .put("content", new JSONObject().put("type", "string")
                                .put("description", "write 操作写入的 UTF-8 文本，其余操作传空字符串"))
                        .put("destination", new JSONObject().put("type", "string")
                                .put("description", "move 的目标目录，其余操作传空字符串")))
                .put("required", new JSONArray().put("action").put("path")
                        .put("content").put("destination"))
                .put("additionalProperties", false);
    }

    private static JSONObject executeWorkspace(JSONObject arguments, AgentRepository repository,
                                               Listener listener) throws JSONException {
        String action = arguments.optString("action");
        String path = arguments.optString("path");
        String content = arguments.optString("content");
        String destination = arguments.optString("destination");
        if (AgentRepository.isDestructiveWorkspaceAction(action)) {
            String preview = "write".equals(action) && content.length() > 300
                    ? content.substring(0, 300) + "…" : content;
            if (!listener.confirmFileOperation(action, path, destination, preview)) {
                return new JSONObject().put("success", false).put("cancelled", true)
                        .put("error", "用户拒绝了文件操作");
            }
        }
        try {
            return repository.executeWorkspaceCommand(action, path, content, destination);
        } catch (Exception error) {
            return new JSONObject().put("success", false)
                    .put("error", error.getMessage() == null ? "文件操作失败" : error.getMessage());
        }
    }

    private static JSONObject tool(String name, String description, JSONObject parameters) throws JSONException {
        if (!parameters.has("type")) parameters.put("type", "object");
        if (!parameters.has("properties")) parameters.put("properties", new JSONObject());
        return new JSONObject().put("type", "function").put("function",
                new JSONObject().put("name", name).put("description", description).put("parameters", parameters));
    }

    private static final class Turn {
        String content = "";
        final List<ToolCall> toolCalls = new ArrayList<>();
    }
    private static final class ToolCall {
        String id = "";
        String name = "";
        String arguments = "";
    }
    private static final class ResponsesTurn {
        String text = "";
        String responseId = "";
        final List<ResponseCall> calls = new ArrayList<>();
    }
    private static final class ResponseCall {
        final String callId, name, arguments;
        ResponseCall(String callId, String name, String arguments) {
            this.callId=callId; this.name=name; this.arguments=arguments;
        }
    }
    private static final class NoOpListener implements Listener {
        @Override public void onStatus(String status) { }
        @Override public void onText(String delta) { }
        @Override public void onToolStart(String callId, String name) { }
        @Override public void onToolEnd(String callId, String name, boolean success) { }
        @Override public void onPasswordMatches(List<AgentRepository.CredentialReference> matches) { }
    }
}
