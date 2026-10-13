package com.dsh.api.dto;

import java.util.List;

/**
 * D-10 工具可达性：全局能力清单快照（区分 tool_search 开关两态）。
 * coreTools 始终 reachable=true；extensions 的 reachable = authorized && toolSearchEnabled。
 */
public record ToolCapabilitySnapshot(
        boolean toolSearchEnabled,
        List<ToolCapabilityEntry> coreTools,
        List<ToolCapabilityEntry> extensions
) {
}
