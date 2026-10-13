package com.dsh.api.dto;

/** 编辑经验条目的请求体：content/scope 可空（空表示保持原值）。 */
public record MemoryEntryUpdate(String content, String scope) {
}
