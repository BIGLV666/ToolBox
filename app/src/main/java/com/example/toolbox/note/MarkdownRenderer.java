package com.example.toolbox.note;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BulletSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.TypefaceSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向笔记阅读页的简化 Markdown 渲染器。
 *
 * <p>它不是完整 Markdown 解析器，只支持行首的一级至三级标题、无序列表、待办项和引用，以及行内粗体
 * 与 {@code http/https} 链接。渲染目标是原生 {@link SpannableStringBuilder}，不执行 HTML，也不使用
 * WebView；未识别或不完整的标记按普通文本保留。</p>
 *
 * <p>块级语法先逐行剥离前缀，再在当前行范围应用字号、粗体、项目符号或引用 Span；行内语法随后以
 * 非贪婪正则扫描并把标记替换为可见文字。该实现不处理嵌套、转义、代码块或相对链接。</p>
 */
public final class MarkdownRenderer {
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|`([^`]+)`|~~(.+?)~~|(?<!\\*)\\*([^*]+)\\*(?!\\*)|\\[(.+?)]\\((https?://.+?)\\)");

    private MarkdownRenderer() { }

    public static SpannableStringBuilder render(String markdown) {
        SpannableStringBuilder result = new SpannableStringBuilder();
        String[] lines = (markdown == null ? "" : markdown).split("\\n", -1);
        boolean codeBlock = false;
        for (String raw : lines) {
            if (raw.trim().startsWith("```")) {
                codeBlock = !codeBlock;
                continue;
            }
            int start = result.length();
            String line = raw;
            int heading = 0;
            boolean bullet = false;
            boolean quote = false;
            // 块前缀互斥：一行只会被识别成标题、列表/待办或引用中的一种。
            if (line.startsWith("### ")) { heading = 3; line = line.substring(4); }
            else if (line.startsWith("## ")) { heading = 2; line = line.substring(3); }
            else if (line.startsWith("# ")) { heading = 1; line = line.substring(2); }
            else if (line.startsWith("- [ ] ")) { bullet = true; line = "☐  " + line.substring(6); }
            else if (line.startsWith("- [x] ") || line.startsWith("- [X] ")) { bullet = true; line = "☑  " + line.substring(6); }
            else if (line.startsWith("- ") || line.startsWith("* ")) { bullet = true; line = line.substring(2); }
            else if (line.startsWith("> ")) { quote = true; line = line.substring(2); }

            appendInline(result, line);
            int end = result.length();
            if (codeBlock && end > start) {
                result.setSpan(new TypefaceSpan("monospace"), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new BackgroundColorSpan(0xFFF0F2F0), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Span 范围不包含随后追加的换行，避免样式自然扩展到下一段。
            if (heading > 0 && end > start) {
                float size = heading == 1 ? 1.55f : heading == 2 ? 1.35f : 1.18f;
                result.setSpan(new RelativeSizeSpan(size), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (bullet && end > start) {
                result.setSpan(new BulletSpan(22), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (quote && end > start) {
                result.setSpan(new QuoteSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (result.length() > 0 || raw.length() == 0) result.append('\n');
        }
        if (result.length() > 0) result.delete(result.length() - 1, result.length());
        return result;
    }

    public static String plainPreview(String markdown) {
        // 列表摘要只做轻量去标记和折行，不复用 Span 渲染，以便 RecyclerView 直接显示纯文本。
        return (markdown == null ? "" : markdown)
                .replaceAll("(?m)^#{1,3}\\s+", "")
                .replaceAll("(?m)^[-*]>?\\s*", "")
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("\\[(.+?)]\\((.+?)\\)", "$1")
                .replace('\n', ' ').trim();
    }

    private static void appendInline(SpannableStringBuilder result, String line) {
        // 每次先追加匹配前的原文，再追加去掉标记的可见文字，并仅给该文字区间设置 Span。
        Matcher matcher = INLINE.matcher(line);
        int cursor = 0;
        while (matcher.find()) {
            result.append(line, cursor, matcher.start());
            int start = result.length();
            if (matcher.group(1) != null) {
                result.append(matcher.group(1));
                result.setSpan(new StyleSpan(Typeface.BOLD), start, result.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(2) != null) {
                result.append(matcher.group(2));
                result.setSpan(new TypefaceSpan("monospace"), start, result.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new BackgroundColorSpan(0xFFF0F2F0), start, result.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(3) != null) {
                result.append(matcher.group(3));
                result.setSpan(new StrikethroughSpan(), start, result.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(4) != null) {
                result.append(matcher.group(4));
                result.setSpan(new StyleSpan(Typeface.ITALIC), start, result.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                result.append(matcher.group(5));
                result.setSpan(new URLSpan(matcher.group(6)), start, result.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            cursor = matcher.end();
        }
        result.append(line, cursor, line.length());
    }
}
