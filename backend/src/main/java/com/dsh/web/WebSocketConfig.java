package com.dsh.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 WebSocket 端点：/ws/v1/events（事件协议 §1）。
 * 握手安全由 {@link LocalHandshakeInterceptor} 负责（Host/Origin 本地边界校验）。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EventWebSocketHandler eventWebSocketHandler;
    private final LocalHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(EventWebSocketHandler eventWebSocketHandler,
                           LocalHandshakeInterceptor handshakeInterceptor) {
        this.eventWebSocketHandler = eventWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(eventWebSocketHandler, "/ws/v1/events")
                .addInterceptors(handshakeInterceptor);
    }
}
