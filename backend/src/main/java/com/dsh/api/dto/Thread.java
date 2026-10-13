package com.dsh.api.dto;

import java.time.Instant;

/** 对应 openapi.json 的 components/schemas/Thread。 */
public record Thread(
        String id,
        String projectId,
        String title,
        String modelProfileId,
        String activeTurnId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
