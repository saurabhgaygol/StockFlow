package com.stockmanagement.printagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One open connection per agent. The server pushes print jobs down it the moment Print is pressed; the agent sends
 * "ping" every ~20 s (answered "pong"), which also keeps the computer shown as online. Results of a print still go
 * back over HTTPS (/api/tag-agent/jobs/{id}/result), so this channel only ever carries "job" messages to the agent.
 * Anything the agent sends other than "ping" is ignored.
 */
@Component
public class TagAgentWsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TagAgentWsHandler.class);
    private static final int MAX_INCOMING = 512;

    private final TagAgentService service;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public TagAgentWsHandler(TagAgentService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    private static Long agentId(WebSocketSession s) {
        Object o = s.getAttributes().get(TagAgentWsHandshake.ATTR_AGENT_ID);
        return o instanceof Long l ? l : null;
    }

    public boolean isConnected(Long agentId) {
        WebSocketSession s = sessions.get(agentId);
        return s != null && s.isOpen();
    }

    /** the computer was removed in Tag Studio: close its connection now (its token is already deleted) */
    public void disconnect(Long agentId) {
        WebSocketSession s = sessions.remove(agentId);
        if (s != null) {
            try { s.close(CloseStatus.POLICY_VIOLATION.withReason("Removed")); } catch (Exception ignored) { }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        Long agentId = agentId(raw);
        if (agentId == null) {
            raw.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        raw.setTextMessageSizeLimit(MAX_INCOMING);
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 256 * 1024);
        raw.getAttributes().put("wrapped", session);
        WebSocketSession old = sessions.put(agentId, session);
        if (old != null && old.isOpen()) {
            try { old.close(CloseStatus.NORMAL.withReason("Replaced by a newer connection")); } catch (Exception ignored) { }
        }
        service.touch(agentId);
        log.info("Printer agent {} connected over WebSocket", agentId);
        drain(agentId);                                   // jobs that were waiting while the agent was offline
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long agentId = agentId(session);
        if (agentId == null) return;
        if (message.getPayloadLength() <= MAX_INCOMING && "ping".equals(message.getPayload())) {
            service.touch(agentId);
            WebSocketSession wrapped = sessions.get(agentId);
            if (wrapped != null) wrapped.sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long agentId = agentId(session);
        if (agentId == null) return;
        Object wrapped = session.getAttributes().get("wrapped");
        // only the current connection counts; an old one closing must not mark a new one offline
        if (wrapped != null && sessions.remove(agentId, wrapped)) {
            service.markOffline(agentId);
            log.info("Printer agent {} disconnected", agentId);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        try { session.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) { }
    }

    /**
     * Sends every waiting job of this agent. A job is marked SENT when taken; if it cannot be written to the
     * connection it is failed at once (so Tag Studio tells the user) instead of being lost.
     */
    public void drain(Long agentId) {
        WebSocketSession session = sessions.get(agentId);
        if (session == null || !session.isOpen()) return;
        while (true) {
            Optional<Map<String, Object>> job = service.claimNext(agentId);
            if (job.isEmpty()) return;
            try {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("type", "job");
                msg.put("job", job.get());
                session.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
            } catch (Exception e) {
                log.warn("Could not push job to agent {}: {}", agentId, e.getMessage());
                Object id = job.get().get("id");
                if (id instanceof Long l) service.finish(agentId, l, false, "The printer agent connection was lost. Please print again.");
                return;
            }
        }
    }
}