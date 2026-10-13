package com.dsh.api.dto;

/** 单个变更文件（openapi.json components/schemas/DiffFile）。 */
public record DiffFile(
        String path,
        String status,
        String oldPath,
        boolean binary
) {
}
