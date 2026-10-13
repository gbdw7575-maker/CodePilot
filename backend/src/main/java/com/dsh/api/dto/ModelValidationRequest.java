package com.dsh.api.dto;

/** 对应 openapi.json 的 components/schemas/ModelValidationRequest。 */
public record ModelValidationRequest(Level level) {

    public enum Level {
        CONNECTIVITY,
        TOOL_ROUNDTRIP
    }
}
