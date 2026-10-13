package com.dsh.api.dto;

import java.time.Instant;
import java.util.List;

/** 对应 openapi.json 的 components/schemas/ModelValidation。 */
public record ModelValidation(
        String profileId,
        Level level,
        boolean passed,
        Instant checkedAt,
        List<ModelValidationCheck> checks,
        Usage usage
) {

    public enum Level {
        CONNECTIVITY,
        TOOL_ROUNDTRIP
    }
}
