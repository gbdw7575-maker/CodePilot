package com.dsh.api.dto;

/** 对应 openapi.json 的 components/schemas/RestoreRequest。 */
public record RestoreRequest(String expectedWorkspaceDigest) {
}
