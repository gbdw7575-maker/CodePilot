package com.dsh.api;

import com.dsh.api.dto.ErrorCode;

import java.util.Map;

/** 业务异常：由 ApiExceptionHandler 按 x-error-codes 映射为统一 ApiError。 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final boolean retryable;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message, boolean retryable, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.retryable = retryable;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** 不可重试、无 details 的常规业务异常。 */
    public ApiException(ErrorCode code, String message) {
        this(code, message, false, Map.of());
    }

    /** 不可重试、带 details 的业务异常。 */
    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        this(code, message, false, details);
    }

    public static ApiException of(ErrorCode code, String message) {
        return new ApiException(code, message, false, Map.of());
    }

    public static ApiException of(ErrorCode code, String message, Map<String, Object> details) {
        return new ApiException(code, message, false, details);
    }

    public ErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }
}
