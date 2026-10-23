package com.stockmanagement.apikey;

import com.stockmanagement.aiagent.ActionService;
import com.stockmanagement.service.AiChatService;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bahar ka darwaza: ChatGPT / Claude / koi bhi software yahan sawaal bhejta hai (API key ke saath).
 *
 * Koi naya AI / data logic NAHI hai. Sawaal seedha maujooda AiChatService.chat(...) ko jaata hai,
 * yaani wahi AI agent jo StockFlow chat me chalta hai: user ki permissions, sirf apni company,
 * ScopeGuard, verified reports, fake-number check, delete kabhi nahi. Wahi final jawab yahan se wapas.
 *
 * Add / edit: agent Confirm card banata hai. Key read-only ho to card turant cancel aur jawab me bata dete hain.
 * Key me add/edit allowed ho to ek Confirm link milta hai; user StockFlow me login karke wahan Confirm dabata hai.
 * Bahar ka software khud kabhi save nahi kar sakta.
 *
 * Endpoints (header: Authorization: Bearer sf_live_...):
 *  GET  /api/v1/me   -> key sahi hai? kis user ki, kya access (connection test ke liye)
 *  POST /api/v1/ask  -> {"message":"...", "conversation_id": optional}
 */
@RestController
@RequestMapping("/api/v1")
public class ExternalApiController {

    private static final Logger log = LoggerFactory.getLogger(ExternalApiController.class);
    private static final int MAX_MESSAGE = 4000;

    private final AiChatService chat;
    private final ActionService actions;
    private final ApiKeyService keys;
    private final AuditLogService audit;

    public ExternalApiController(AiChatService chat, ActionService actions, ApiKeyService keys, AuditLogService audit) {
        this.chat = chat;
        this.actions = actions;
        this.keys = keys;
        this.audit = audit;
    }

    public record AskRequest(String message, Long conversation_id) {}

    /* ---------------- connection test ---------------- */

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal CustomUserDetails me, HttpServletRequest req) {
        ApiKey k = key(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", me.getUsername());
        out.put("company", me.getUser().getCompanyName());
        out.put("key_name", k.getName());
        out.put("access", k.getCanWrite() ? "read + add/edit (each change must be confirmed inside StockFlow)" : "read only");
        out.put("expires_at", k.getExpiresAt() == null ? "never" : k.getExpiresAt().toString());
        return out;
    }

    /* ---------------- sawaal ---------------- */

    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@AuthenticationPrincipal CustomUserDetails me,
                                                   @RequestBody(required = false) AskRequest body,
                                                   HttpServletRequest req) {
        ApiKey k = key(req);
        String message = body == null || body.message() == null ? "" : body.message().trim();
        if (message.isEmpty()) return bad(400, "\"message\" is required.");
        if (message.length() > MAX_MESSAGE) return bad(400, "\"message\" is too long (max " + MAX_MESSAGE + " characters).");

        String actor = me.getUsername() + " (via API key: " + k.getName() + ")";
        Object ipAttr = req.getAttribute(ApiKeyAuthFilter.ATTR_IP);
        String ip = ipAttr == null ? req.getRemoteAddr() : ipAttr.toString();

        AiChatService.ChatResult r;
        try {
            // Wahi chat jo StockFlow me chalta hai (Auto model, koi file/photo nahi)
            r = chat.chat(me.getUserId(), body.conversation_id(), message, message, false, null);
        } catch (IllegalArgumentException e) {
            return bad(400, "Conversation not found.");
        } catch (RuntimeException e) {
            log.warn("External API ask failed for user {}: {}", me.getUserId(), e.getMessage());
            return bad(500, "Something went wrong. Please try again.");
        }

        String reply = r.reply() == null ? "" : r.reply();
        String confirmUrl = null;

        ActionService.ProposalView p = r.proposal();
        if (p != null) {
            if (k.getCanWrite()) {
                String linkId = keys.registerConfirm(me.getUserId(), p, k.getName());
                confirmUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/ai-confirm/").path(linkId).toUriString();
                reply = reply + "\n\nNothing is saved yet. Open this link, sign in to StockFlow, check the details and press Confirm "
                        + "(valid for 10 minutes, one time only): " + confirmUrl;
            } else {
                actions.cancel(me.getUserId(), p.token());
                reply = "This API key is read only, so I cannot add or change data through it. "
                        + "Please do this inside StockFlow, or ask for a key with add/edit access.";
            }
        }
        if (r.file() != null || r.codeReport() != null) {
            reply = reply + "\n\n(Excel / PDF / PowerPoint files can be downloaded inside StockFlow chat only.)";
        }

        try {
            audit.log(me.getUserId(), actor, "API_ASK", "AI", "External API",
                    "Asked via API key " + k.getKeyPrefix() + "...: " + cut(message, 150)
                            + (p == null ? "" : (k.getCanWrite() ? " [confirm link sent]" : " [add/edit blocked: read-only key]")),
                    ip, r.conversationId(), "AI_CONVERSATION");
        } catch (RuntimeException e) {
            log.warn("External API audit failed: {}", e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", reply);
        out.put("conversation_id", r.conversationId());
        out.put("confirm_url", confirmUrl);
        return ResponseEntity.ok(out);
    }

    /* ---------------- helpers ---------------- */

    private static ApiKey key(HttpServletRequest req) {
        Object k = req.getAttribute(ApiKeyAuthFilter.ATTR_KEY);
        if (!(k instanceof ApiKey key)) throw new IllegalStateException("API key missing");   // filter ke bina yahan aana namumkin
        return key;
    }

    private static ResponseEntity<Map<String, Object>> bad(int status, String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", msg);
        return ResponseEntity.status(status).body(out);
    }

    private static String cut(String s, int n) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > n ? one.substring(0, n) + "..." : one;
    }
}