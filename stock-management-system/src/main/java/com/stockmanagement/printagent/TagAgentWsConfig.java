package com.stockmanagement.printagent;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket for the Printer Agent: /api/tag-agent/ws. It sits next to the existing STOMP /ws (notifications), which is
 * not changed. The connection is opened by the agent program with "Authorization: Bearer <agent token>"; the request
 * first passes TagAgentSecurityConfig's chain (token check, HTTPS rule), so a connection without a valid token never
 * reaches the handler. No allowed origins are added: a web page in a browser can never open it (a browser cannot send
 * that header, and cross-origin is refused).
 */
@Configuration
@EnableWebSocket
public class TagAgentWsConfig implements WebSocketConfigurer {

    private final TagAgentWsHandler handler;

    public TagAgentWsConfig(TagAgentWsHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/tag-agent/ws").addInterceptors(new TagAgentWsHandshake());
    }
}