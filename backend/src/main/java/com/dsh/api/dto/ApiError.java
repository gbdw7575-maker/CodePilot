package com.dsh.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 统一错误体，对应 openapi.json 的 components/schemas/ApiError。
 * 所有字段必填；details 不为 null（未知时用空对象）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiError(
        ErrorCode code,
        String message,
        String requestId,
        boolean retryable,
        Map<String, Object> details
) {

    public static ApiError of(ErrorCode code, String message, String requestId, boolean retryable) {
        return new ApiError(code, message, requestId, retryable, Map.of());
    }

    public ApiError {
        details = details == null ? Map.of() : details;
    }
}
