package com.dsh.api.dto;

import java.time.Instant;

/** 受控产物元数据（openapi.json components/schemas/Artifact）。 */
public record Artifact(
        String id,
        String runId,
        String projectId,
        String name,
        String mediaType,
        long sizeBytes,
        String sha256,
        Instant createdAt,
        boolean downloadable
) {
}
