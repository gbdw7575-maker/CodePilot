package com.dsh.memory;

/**
 * memory_recall 的有界索引读取结果（开发文档 §10.3）：
 * 读取上限 200 行 / 25,000 UTF-8 字节先到者，按整行边界截断并标记 truncated。
 */
public record MemoryIndex(long version, boolean enabled, int entryCount,
                          String text, boolean truncated) {
}
