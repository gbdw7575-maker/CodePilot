package com.dsh.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求分配不透明 X-Request-Id：客户端不得解析；错误体中的 requestId 与响应头一致。
 * 格式满足契约 ^[A-Za-z0-9_-]+$，长度 ≤ 128。
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    public static String currentRequestId() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "req-unknown" : id;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // WebSocket 升级请求不经过此过滤器，避免干扰 Upgrade 头
        return "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = newRequestId();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
