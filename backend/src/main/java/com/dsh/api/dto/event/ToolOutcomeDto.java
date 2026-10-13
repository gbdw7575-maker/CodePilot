package com.dsh.api.dto.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 对应 events.schema.json 的 ToolOutcome。
 * D-12：新增 isError（MCP 映射）、callId（调用配对）、source（结果来源）。
 */
public record ToolOutcomeDto(
        String status,
        String errorCode,
        String summary,
        List<ContentBlock> content,
        JsonNode structuredContent,
        List<String> artifactIds,
        boolean truncated,
        int durationMs,
        Integer exitCode,
        boolean isError,
        String callId,
        String source
) {
}
