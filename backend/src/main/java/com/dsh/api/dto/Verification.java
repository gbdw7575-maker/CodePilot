package com.dsh.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 验证结果 DTO。
 */
public record Verification(
        String id,
        String runId,
        String planVersion,
        String workspaceDigest,
        String preVerificationDigest,
        String imageDigest,
        String commandSummary,
        String status,
        int exitCode,
        int testsDiscovered,
        int testsPassed,
        int testsSkipped,
        boolean workspaceModified,
        boolean stale,
        List<String> logArtifactIds,
        Instant startedAt,
        Instant finishedAt
) {
}
