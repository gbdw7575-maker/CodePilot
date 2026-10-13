package com.dsh.memory;

import java.time.Instant;
import java.util.List;

/**
 * Auto Memory 完整视图（REST 查看与启停接口的返回体）。
 * version 为并发版本（If-Match 依据）；indexText 为渲染后的 MEMORY.md 索引（有界）。
 */
public record MemoryView(String projectId, long version, boolean enabled, Instant updatedAt,
                         List<MemoryEntry> entries, String indexText, boolean indexTruncated) {
}
