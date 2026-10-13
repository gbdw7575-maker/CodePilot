package com.dsh.api.dto;

import java.time.Instant;

/** 对应 openapi.json 的 components/schemas/Message；sequence 为十进制字符串。 */
public record Message(
        String id,
        String threadId,
        String turnId,
        String role,
        String content,
        String sequence,
        Instant createdAt
) {
}
