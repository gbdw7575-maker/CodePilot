package com.dsh.api.dto;

import java.time.Instant;

/** 对应 openapi.json 的 components/schemas/InputAccepted。 */
public record InputAccepted(String messageId, String turnId, String status, Instant createdAt) {
}
