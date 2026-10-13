package com.dsh.api.dto;

import java.time.Instant;

/** 对应 openapi.json 的 components/schemas/Run。 */
public record Run(
        String id,
        String turnId,
        String parentRunId,
        String traceId,
        RunRole role,
        RunState state,
        VerificationStatus verificationStatus,
        StopReason stopReason,
        int attempt,
        String workspaceRef,
        String configDigest,
        BudgetView budget,
        Usage usage,
        String summary,
        String completionBasis,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
