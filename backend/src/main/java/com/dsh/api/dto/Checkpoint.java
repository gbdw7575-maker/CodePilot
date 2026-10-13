package com.dsh.api.dto;

import java.time.Instant;
import java.util.List;

/** 对应 openapi.json 的 components/schemas/Checkpoint。 */
public record Checkpoint(
        String id,
        String runId,
        String workspaceDigest,
        List<String> artifactIds,
        Instant createdAt,
        int version
) {
}
