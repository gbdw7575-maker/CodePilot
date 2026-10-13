package com.dsh.api.dto;

/** 与 openapi.json 的 components/schemas/VerificationStatus 一致。 */
public enum VerificationStatus {
    PENDING,
    PASSED,
    FAILED,
    UNVERIFIED,
    ENV_ERROR,
    NOT_REQUIRED
}
