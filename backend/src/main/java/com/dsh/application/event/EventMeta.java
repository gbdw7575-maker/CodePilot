package com.dsh.application.event;

/** 事件公共归属字段；turnId/runId/parentRunId/traceId/spanId 可空（用 null）。 */
public record EventMeta(
        String projectId,
        String threadId,
        String turnId,
        String runId,
        String parentRunId,
        String traceId,
        String spanId
) {
}
