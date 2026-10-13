package com.dsh.api.dto;

import java.time.Instant;

/** 对应 openapi.json 的 components/schemas/ModelProfile。 */
public record ModelProfile(
        String id,
        String name,
        Provider provider,
        String model,
        String baseUrl,
        String credentialRef,
        ModelOptions options,
        CapabilityStatus capabilityStatus,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
