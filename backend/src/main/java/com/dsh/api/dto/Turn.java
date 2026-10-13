package com.dsh.api.dto;

import java.time.Instant;

/** 对应 openapi.json 的 components/schemas/Turn。 */
public record Turn(
        String id,
        String threadId,
        String rootRunId,
        String input,
        RunState state,
        VerificationStatus verificationStatus,
        StopReason stopReason,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
