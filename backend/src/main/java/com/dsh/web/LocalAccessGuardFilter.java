package com.dsh.web;

import com.dsh.api.dto.ApiError;
import com.dsh.api.dto.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * 本地访问与写请求防护（API-06）：
 * - Host 必须是 loopback；Origin 出现时必须是本机 http 地址；
 * - 写请求必须携带 X-DSH-Client: local-ui（同源防跨站标记，不是身份凭据）；
 * - 写请求体 UTF-8 上限 1 MiB，超出 413；同时缓存请求体供幂等摘要使用。
 * 该检查先于幂等重放与业务校验执行。
 */
public class LocalAccessGuardFilter extends OncePerRequestFilter {

    public static final long MAX_BODY_BYTES = 1024L * 1024L;
    public static final String CLIENT_HEADER = "X-DSH-Client";
    public static final String CLIENT_VALUE = "local-ui";

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1");

    private final ObjectMapper mapper;

    public LocalAccessGuardFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean write = WRITE_METHODS.contains(request.getMethod());

        if (write) {
            long contentLength = request.getContentLengthLong();
            if (contentLength > MAX_BODY_BYTES) {
                deny(response, 413, ErrorCode.INPUT_TOO_LARGE, "请求体超过 1 MiB 上限");
                return;
            }
            // Host 校验（有头时校验；本地 CLI 无 Origin 也必须通过 loopback 校验）
            String host = hostOf(request.getHeader("Host"));
            if (host != null && !isLoopback(host)) {
                deny(response, 403, ErrorCode.ORIGIN_DENIED, "Host 必须是本机回环地址");
                return;
            }
            String origin = request.getHeader("Origin");
            if (origin != null && !isLocalOrigin(origin)) {
                deny(response, 403, ErrorCode.ORIGIN_DENIED, "Origin 不是本机受信来源");
                return;
            }
            String client = request.getHeader(CLIENT_HEADER);
            if (!CLIENT_VALUE.equals(client)) {
                deny(response, 403, ErrorCode.ORIGIN_DENIED,
                        "写请求必须携带 X-DSH-Client: local-ui");
                return;
            }
        } else {
            String host = hostOf(request.getHeader("Host"));
            if (host != null && !isLoopback(host)) {
                deny(response, 403, ErrorCode.ORIGIN_DENIED, "Host 必须是本机回环地址");
                return;
            }
        }

        HttpServletRequest toUse = request;
        if (write) {
            try {
                toUse = new CachedBodyRequestWrapper(request, MAX_BODY_BYTES);
            } catch (IOException e) {
                deny(response, 413, ErrorCode.INPUT_TOO_LARGE, "请求体超过 1 MiB 上限");
                return;
            }
        }
        filterChain.doFilter(toUse, response);
    }

    private boolean isLocalOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            return "http".equals(uri.getScheme()) && uri.getHost() != null && isLoopback(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String hostOf(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String host = hostHeader.trim();
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end < 0 ? host : host.substring(1, end);
        }
        int colon = host.lastIndexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private boolean isLoopback(String host) {
        return LOOPBACK_HOSTS.contains(host.toLowerCase());
    }

    private void deny(HttpServletResponse response, int status, ErrorCode code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ApiError body = ApiError.of(code, message, RequestIdFilter.currentRequestId(), false);
        mapper.writeValue(response.getWriter(), body);
    }
}
