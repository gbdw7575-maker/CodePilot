package com.dsh.api.dto;

import java.util.List;

/** 工作区相对基线差异（openapi.json components/schemas/Diff）。 */
public record Diff(
        String runId,
        String baseDigest,
        String workspaceDigest,
        List<DiffFile> files,
        String patch,
        boolean truncated,
        String artifactId
) {
}
