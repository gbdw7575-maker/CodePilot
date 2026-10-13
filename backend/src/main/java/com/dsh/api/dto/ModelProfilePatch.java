package com.dsh.api.dto;

/**
 * 对应 openapi.json 的 components/schemas/ModelProfilePatch（minProperties=1）。
 * null 表示未提供该字段；credentialRef 传 null 与不传等价。
 */
public record ModelProfilePatch(
        String name,
        Provider provider,
        String model,
        String baseUrl,
        String credentialRef,
        ModelOptions options
) {
}
