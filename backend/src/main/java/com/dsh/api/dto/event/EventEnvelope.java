package com.dsh.api.dto.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 对应 doc/events.schema.json 的 EventEnvelope（oneOf 判别字段 type）。
 * payload 按事件类型严格构造；sequence 为正整数十进制字符串。
 * traceId/spanId 为本地链路关联字段（DSH-003），可空。
 */
public record EventEnvelope(
        String eventId,
        String projectId,
        String threadId,
        String turnId,
        String runId,
        String parentRunId,
        String traceId,
        String spanId,
        String sequence,
        Instant timestamp,
        String schemaVersion,
        String type,
        JsonNode payload
) {
}
