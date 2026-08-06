package com.example.toolbox.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.SAXParserFactory;

/** Extracts paragraph and table text from Word Open XML (.docx) documents. */
public final class WordParse {
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 5_000_000;
    private final Context context;

    public WordParse(Context context) {
        this.context = context.getApplicationContext();
    }

    public String parseFromUri(Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        try (AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r")) {
            if (descriptor != null && descriptor.getLength() > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("Word 文件不能超过 20 MB");
            }
        }
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("无法读取 Word 文件");
            return parse(input);
        }
    }

    static String parse(InputStream input) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) return parseDocumentXml(zip);
            }
        }
        throw new IllegalArgumentException("不是有效的 .docx Word 文件");
    }

    private static String parseDocumentXml(InputStream input) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentHandler handler = new DocumentHandler();
        factory.newSAXParser().parse(input, handler);
        String result = handler.text.toString().trim();
        if (result.isEmpty()) throw new IllegalArgumentException("Word 文件中没有可读取的文字");
        return result;
    }

    private static final class DocumentHandler extends DefaultHandler {
        final StringBuilder text = new StringBuilder();
        boolean readingText;
        int tableDepth;

        @Override public void startElement(String uri, String localName, String qName,
                                           Attributes attributes) {
            String name = localName.isEmpty() ? stripPrefix(qName) : localName;
            if ("tbl".equals(name)) tableDepth++;
            else if ("t".equals(name)) readingText = true;
            else if ("tab".equals(name)) text.append('\t');
            else if ("br".equals(name) || "cr".equals(name)) appendSeparator(text, '\n');
        }

        @Override public void characters(char[] chars, int start, int length) {
            if (readingText) text.append(chars, start, length);
            if (text.length() > MAX_TEXT_CHARS) {
                throw new IllegalArgumentException("Word 文件解析后的文字过长");
            }
        }

        @Override public void endElement(String uri, String localName, String qName) {
            String name = localName.isEmpty() ? stripPrefix(qName) : localName;
            if ("t".equals(name)) readingText = false;
            else if ("tc".equals(name)) appendCellSeparator(text);
            else if ("tr".equals(name)) finishTableRow(text);
            else if ("p".equals(name) && tableDepth == 0) appendSeparator(text, '\n');
            else if ("tbl".equals(name)) {
                tableDepth--;
                appendSeparator(text, '\n');
            }
        }

        private static String stripPrefix(String name) {
            int separator = name.indexOf(':');
            return separator < 0 ? name : name.substring(separator + 1);
        }
    }

    private static void appendCellSeparator(StringBuilder text) {
        trimTrailingWhitespace(text);
        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append(" | ");
    }

    private static void finishTableRow(StringBuilder text) {
        trimTrailingWhitespace(text);
        if (text.length() >= 1 && text.charAt(text.length() - 1) == '|') {
            text.setLength(text.length() - 1);
            trimTrailingWhitespace(text);
        }
        appendSeparator(text, '\n');
    }

    private static void trimTrailingWhitespace(StringBuilder text) {
        while (text.length() > 0) {
            char value = text.charAt(text.length() - 1);
            if (value != ' ' && value != '\t') return;
            text.setLength(text.length() - 1);
        }
    }

    private static void appendSeparator(StringBuilder text, char separator) {
        if (text.length() > 0 && text.charAt(text.length() - 1) != separator) text.append(separator);
    }
}
