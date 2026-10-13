package com.dsh.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * WebSocket 握手安全拦截器（事件协议 §1）：
 * - Host 必须是 loopback；
 * - Origin 出现时必须是本机 http 来源；
 * - 不要求 X-DSH-Client 头（浏览器 WebSocket 不能自由加请求头）。
 */
@Component
public class LocalHandshakeInterceptor implements HandshakeInterceptor {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "::1");

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        HttpServletRequest http = servletRequest.getServletRequest();

        String host = hostOf(http.getHeader("Host"));
        if (host != null && !isLoopback(host)) {
            return false; // 拒绝非 loopback Host
        }
        String origin = http.getHeader("Origin");
        if (origin != null && !isLocalOrigin(origin)) {
            return false; // 拒绝非本地 Origin
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
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
}
