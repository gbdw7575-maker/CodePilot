package com.dsh.api.dto;

import java.util.List;

/**
 * D-10 工具可达性：指定 Run 的模型可见工具快照。
 * modelVisibleTools = 核心工具 + 本 Run 已挂载且授权的扩展工具。
 */
public record RunToolSnapshot(
        String runId,
        List<ToolCapabilityEntry> modelVisibleTools
) {
}
