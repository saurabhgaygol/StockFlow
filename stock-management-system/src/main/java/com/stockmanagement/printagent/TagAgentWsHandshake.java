package com.stockmanagement.printagent;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** Lets the handshake through only when TagAgentAuthFilter already found a paired agent for the token. */
public class TagAgentWsHandshake implements HandshakeInterceptor {

    static final String ATTR_AGENT_ID = "agentId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest s) {
            Object a = s.getServletRequest().getAttribute(TagAgentAuthFilter.ATTR_AGENT);
            if (a instanceof TagAgent agent && agent.getId() != null) {
                attributes.put(ATTR_AGENT_ID, agent.getId());
                return true;
            }
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        // nothing
    }
}