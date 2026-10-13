package com.dsh.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义（内部接口契约 §5）：统一 name/description/inputSchema/version/category/sideEffect。
 * MVP 核心工具定义与 doc/tools.schema.json 的 x-tools 同源；定义不是权限系统。
 */
public record ToolDefinition(String name, String description, String version,
                             String category, String sideEffect, JsonNode inputSchema) {
}
