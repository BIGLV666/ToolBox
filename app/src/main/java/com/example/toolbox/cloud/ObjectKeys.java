package com.example.toolbox.cloud;

import java.util.Locale;

/**
 * 远端对象键的生成规则。
 *
 * <p>附件键形如 {@code notes/2026年报表.pdf}，直接使用原始文件名：这样在七牛控制台里能一眼认出是哪个
 * 文件，不必再回应用里对照一串 UUID。文件名本身<b>不加密</b>，加密的是文件内容，这也是有意为之——
 * 名字用于辨认，内容用于保密。</p>
 *
 * <p>笔记正文的键形如 {@code notes/meta/&lt;syncId&gt;.json}：这里没有"文件名"可供辨认，而用标题当键会在
 * 改标题时让对象位置跟着变，反而容易产生孤儿对象，因此保持用稳定的 syncId。</p>
 */
public final class ObjectKeys {
    /** 文件名安全化后的最大长度，避免超长键在部分工具里出错。 */
    private static final int MAX_NAME_LENGTH = 120;
    private static final String FALLBACK_NAME = "附件";

    private ObjectKeys() { }

    /** 附件对象键：{@code <前缀><安全化的原始文件名>}。 */
    public static String attachmentKey(String prefix, String originalName) {
        return normalizePrefix(prefix) + sanitize(originalName);
    }

    /** 笔记正文对象键：{@code <前缀>meta/<syncId>.json}。 */
    public static String noteMetaKey(String prefix, String syncId) {
        return normalizePrefix(prefix) + "meta/" + syncId + ".json";
    }

    /** 笔记正文的对象键前缀，用于列举与恢复。 */
    public static String noteMetaPrefix(String prefix) {
        return normalizePrefix(prefix) + "meta/";
    }

    /** 加密凭据（keyring）对象键。 */
    public static String keyringKey(String prefix) {
        return normalizePrefix(prefix) + "_meta/keyring.txt";
    }

    /**
     * 把文件名变成可安全放进对象键的形式。
     *
     * <p>替换掉路径分隔符与控制字符：{@code /} 会让键凭空多出一层"目录"，其余字符在 URL 与部分客户端
     * 里也有歧义。保留中文与空格——它们在对象键里是合法的，而改名会让用户认不出自己的文件。</p>
     */
    public static String sanitize(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            return FALLBACK_NAME;
        }
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                continue;
            }
            if (ch == '\\' || ch == '/' || ch == ':' || ch == '*' || ch == '?'
                    || ch == '"' || ch == '<' || ch == '>' || ch == '|') {
                cleaned.append('_');
            } else {
                cleaned.append(ch);
            }
        }
        String result = cleaned.toString().trim();
        if (result.isEmpty() || ".".equals(result) || "..".equals(result)) {
            return FALLBACK_NAME;
        }
        return truncate(result);
    }

    /** 提取小写扩展名；没有扩展名或扩展名异常长时返回空串。 */
    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String extension = name.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,10}") ? extension : "";
    }

    /** 截断超长文件名，但保住扩展名，否则文件到了云端会失去类型线索。 */
    private static String truncate(String name) {
        if (name.length() <= MAX_NAME_LENGTH) {
            return name;
        }
        String extension = extensionOf(name);
        String suffix = extension.isEmpty() ? "" : "." + extension;
        int keep = Math.max(1, MAX_NAME_LENGTH - suffix.length());
        return name.substring(0, keep) + suffix;
    }

    private static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (!value.isEmpty() && !value.endsWith("/")) {
            value = value + "/";
        }
        return value;
    }
}
