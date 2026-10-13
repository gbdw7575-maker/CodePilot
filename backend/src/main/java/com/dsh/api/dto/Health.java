package com.dsh.api.dto;

/**
 * 健康检查响应，对应 openapi.json 的 components/schemas/Health。
 */
public record Health(
        Status status,
        String contractVersion,
        java.time.Instant timestamp
) {

    public enum Status {
        UP,
        DEGRADED
    }
}
