package com.dsh.api.dto;

import java.time.Instant;
import java.util.List;

/** 对应 openapi.json 的 components/schemas/RestoreResult。 */
public record RestoreResult(
        String checkpointId,
        String previousWorkspaceDigest,
        String workspaceDigest,
        List<String> invalidatedVerificationIds,
        Instant restoredAt
) {
}
