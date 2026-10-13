package com.dsh.web;

import com.dsh.api.ApiException;
import com.dsh.api.dto.ApiError;
import com.dsh.api.dto.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一错误出口：所有错误以 ApiError 返回（含 X-Request-Id 头），
 * 不把业务失败包装成 HTTP 200，不向客户端回传堆栈与主机敏感信息。
 * 状态码严格按 openapi.json x-error-codes 映射。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return error(statusOf(ex.code()), ex.code(), ex.getMessage(), ex.retryable(), ex.details());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Content-Type 必须为 application/json", false);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "资源不存在", false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.INVALID_INPUT, "请求方法不支持", false);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求体不是可解析的 JSON", false);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "请求参数校验失败", false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        String requestId = RequestIdFilter.currentRequestId();
        log.error("未处理异常 requestId={}", requestId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCode.INTERNAL_ERROR, "服务内部错误", requestId, false));
    }

    private ResponseEntity<ApiError> error(HttpStatus status, ErrorCode code, String message, boolean retryable) {
        return error(status, code, message, retryable, java.util.Map.of());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, ErrorCode code, String message, boolean retryable,
                                           java.util.Map<String, Object> details) {
        ApiError body = new ApiError(code, message, RequestIdFilter.currentRequestId(), retryable, details);
        return ResponseEntity.status(status).body(body);
    }

    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED, INVALID_CURSOR -> HttpStatus.BAD_REQUEST;
            case INPUT_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ORIGIN_DENIED, PERMISSION_DENIED, PROJECT_NOT_TRUSTED, PATH_OUTSIDE_WORKSPACE -> HttpStatus.FORBIDDEN;
            case INVALID_INPUT, CREDENTIAL_UNAVAILABLE, MODEL_CAPABILITY_UNVERIFIED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case ACTIVE_TURN_EXISTS, RESOURCE_BUSY, INVALID_STATE, IDEMPOTENCY_CONFLICT, IDEMPOTENCY_IN_PROGRESS,
                    APPROVAL_STALE, APPROVAL_ALREADY_DECIDED, WORKSPACE_CONFLICT, MEMORY_DISABLED ->
                    HttpStatus.CONFLICT;
            case EVENT_CURSOR_EXPIRED -> HttpStatus.GONE;
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
            case VERSION_CONFLICT -> HttpStatus.PRECONDITION_FAILED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case PROVIDER_UNAVAILABLE, SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
