package com.dsh.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 任务副本内文本文件读取约定：UTF-8、二进制探测、按行切分（不保留行尾符）。 */
public final class TextFiles {

    /** 单文件大小保护：32 MiB 以上不做文本读取/搜索。 */
    public static final long MAX_TEXT_BYTES = 32L * 1024 * 1024;

    private TextFiles() {
    }

    public static boolean isProbablyBinary(byte[] content) {
        int probe = Math.min(content.length, 8192);
        for (int i = 0; i < probe; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }

    public static List<String> readLines(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        // 与编辑器一致：去掉唯一的结尾换行；\r\n 的 \r 在切分后剔除
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split("\n", -1))
                .map(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l)
                .toList();
    }
}
