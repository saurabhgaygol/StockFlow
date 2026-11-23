package com.stockmanagement.printagent;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps each agent's "any job for me?" request open for up to ~25 seconds (long poll). When a user presses Print the
 * waiting request is answered at once, so a tag starts printing about a second later, with no repeated asking.
 */
@Component
public class TagAgentHub {

    private final TagAgentService service;
    private final TagAgentWsHandler ws;
    private final ConcurrentHashMap<Long, DeferredResult<ResponseEntity<Map<String, Object>>>> waiters = new ConcurrentHashMap<>();

    public TagAgentHub(TagAgentService service, TagAgentWsHandler ws) {
        this.service = service;
        this.ws = ws;
    }

    private static ResponseEntity<Map<String, Object>> none() {
        return ResponseEntity.noContent().build();
    }

    public DeferredResult<ResponseEntity<Map<String, Object>>> await(Long agentId, int seconds) {
        DeferredResult<ResponseEntity<Map<String, Object>>> dr = new DeferredResult<>(seconds * 1000L, none());
        DeferredResult<ResponseEntity<Map<String, Object>>> old = waiters.put(agentId, dr);
        if (old != null) old.setResult(none());                       // the agent asked again; the older request ends
        dr.onCompletion(() -> waiters.remove(agentId, dr));
        dr.onTimeout(() -> waiters.remove(agentId, dr));
        if (service.hasQueued(agentId)) wake(agentId);                // a job came in while this request was starting
        return dr;
    }

    /** a computer was removed: drop its open WebSocket and any waiting request */
    public void kick(Long agentId) {
        ws.disconnect(agentId);
        DeferredResult<ResponseEntity<Map<String, Object>>> dr = waiters.remove(agentId);
        if (dr != null) dr.setResult(none());
    }

    /** a job was queued for this agent: push it over the WebSocket, or else hand it to the waiting long-poll request */
    public void wake(Long agentId) {
        if (ws.isConnected(agentId)) {                      // WebSocket open: push the job down it
            ws.drain(agentId);
            return;
        }
        DeferredResult<ResponseEntity<Map<String, Object>>> dr = waiters.remove(agentId);
        if (dr == null) return;
        Optional<Map<String, Object>> job = service.claimNext(agentId);
        dr.setResult(job.isPresent() ? ResponseEntity.ok(job.get()) : none());
    }
}