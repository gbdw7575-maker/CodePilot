package com.dsh.api.dto;

import java.time.Instant;

/** 对应 openapi.json 的 components/schemas/Project。 */
public record Project(
        String id,
        String name,
        String rootPath,
        boolean trusted,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
