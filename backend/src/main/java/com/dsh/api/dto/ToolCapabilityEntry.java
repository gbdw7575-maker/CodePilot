package com.dsh.api.dto;

/**
 * D-10 工具可达性：单个工具能力条目。
 * reachable 表示当前是否可被模型发现并执行（核心工具恒为 true；扩展工具需 authorized 且 tool_search 开启）。
 */
public record ToolCapabilityEntry(
        String name,
        String description,
        String category,
        String sideEffect,
        boolean extension,
        boolean authorized,
        boolean reachable
) {
}
