package com.dsh.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * D-10 工具可达性：公共 API 工具定义（对应 openapi.json ToolDefinition）。
 * mounted 表示该工具对当前 Run 是否模型可见（核心工具恒 true；扩展工具需已挂载且授权）。
 */
public record ToolDefinitionDto(
        String name,
        String description,
        JsonNode inputSchema,
        String version,
        String category,
        String sideEffect,
        boolean mounted
) {
}
