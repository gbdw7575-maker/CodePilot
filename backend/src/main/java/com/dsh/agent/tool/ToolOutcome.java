package com.dsh.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * 工具统一结果（内部形态；Dispatcher 边界映射为事件 ToolOutcome DTO）。
 * status 与 tools.schema.json ToolOutcome 枚举一致：
 * SUCCEEDED/FAILED/DENIED/CANCELLED/UNKNOWN/SKIPPED；工具失败也必须回填 callId。
 * D-12：isError（MCP 映射，非 SUCCEEDED 为 true）、source（结果来源：工具名/MCP server）。
 */
public record ToolOutcome(String status, String errorCode, String summary, String text,
                          JsonNode structuredContent, boolean truncated, long durationMs,
                          Integer exitCode, boolean isError, String source) {

    public static ToolOutcome succeeded(String summary, String text, JsonNode structured, String source) {
        return new ToolOutcome("SUCCEEDED", null, summary, text,
                structured == null ? NullNode.instance : structured, false, 0, null, false, source);
    }

    public static ToolOutcome failed(String errorCode, String summary, String source) {
        return new ToolOutcome("FAILED", errorCode, summary, summary, NullNode.instance, false, 0, null, true, source);
    }

    public static ToolOutcome denied(String errorCode, String summary, String source) {
        return new ToolOutcome("DENIED", errorCode, summary, summary, NullNode.instance, false, 0, null, true, source);
    }

    public ToolOutcome withTiming(long durationMs) {
        return new ToolOutcome(status, errorCode, summary, text, structuredContent,
                truncated, durationMs, exitCode, isError, source);
    }

    public ToolOutcome withExit(Integer exit, boolean truncated) {
        return new ToolOutcome(status, errorCode, summary, text, structuredContent,
                truncated, durationMs, exit, isError, source);
    }
}
