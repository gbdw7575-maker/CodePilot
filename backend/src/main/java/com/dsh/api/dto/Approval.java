package com.dsh.api.dto;

import java.time.Instant;

/** 对应 openapi.json 的 components/schemas/Approval。 */
public record Approval(
        String id,
        String turnId,
        String runId,
        String toolCallId,
        String toolName,
        String argumentsSummary,
        String parametersDigest,
        String workspaceDigest,
        Risk risk,
        String reason,
        String scope,
        ApprovalStatus status,
        Instant expiresAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
