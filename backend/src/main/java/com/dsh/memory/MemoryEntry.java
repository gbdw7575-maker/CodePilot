package com.dsh.memory;

import java.time.Instant;

/**
 * 一条自动经验（开发文档 §10.3）：category + 正文 + 来源引用 + 更新时间 + 有效范围。
 * category 白名单 user/feedback/project/reference；经验只落经验层，
 * 规则文件（AGENTS.md/CLAUDE.md）永不由此路径写入。
 */
public record MemoryEntry(String id, String category, String content, String scope,
                          String sourceRef, Instant updatedAt) {
}
