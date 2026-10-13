package com.dsh.api.dto;

/** 对应 openapi.json 的 components/schemas/ModelProfileWrite。 */
public record ModelProfileWrite(
        String name,
        Provider provider,
        String model,
        String baseUrl,
        String credentialRef,
        ModelOptions options
) {
}
