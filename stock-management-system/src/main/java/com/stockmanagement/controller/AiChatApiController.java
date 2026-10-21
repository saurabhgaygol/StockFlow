package com.stockmanagement.controller;

import com.stockmanagement.aiagent.ActionService;
import com.stockmanagement.service.AiChatClient;
import com.stockmanagement.service.AiChatService;
import com.stockmanagement.service.AiFileReader;
import org.springframework.beans.factory.annotation.Autowired;
import com.stockmanagement.service.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * JSON API jo ai-chat.html use karta hai. Sirf login user, aur sirf apni chats.
 * CSRF: chat page par csrf meta tag nahi hai, isliye /api/ai/** ke liye token ki jagah
 * same-origin (Origin/Referer) check lagaya hai (SecurityConfig me bhi ignore set hai).
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatApiController {

    private static final int MAX_MESSAGE = 4000;
    private static final int MAX_FILES = 5;
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_FILE_TEXT = 20000;
    private static final Set<String> TEXT_EXT = Set.of("txt", "csv", "md", "json", "log", "xml", "html", "tsv");

    private final AiChatService chat;

    public AiChatApiController(AiChatService chat) {
        this.chat = chat;
    }

    /* ---- NEW: PDF / Excel / Word / photo reading and AI add-edit Confirm (old code below is unchanged) ---- */
    private static final int MAX_IMAGES = 3;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    @Autowired private AiFileReader reader;
    @Autowired private ActionService actions;

    public record TokenRequest(String token) {}

    @PostMapping("/action/confirm")
    public ResponseEntity<?> confirmAction(@AuthenticationPrincipal CustomUserDetails me,
                                           @RequestBody TokenRequest body, HttpServletRequest req) {
        sameOrigin(req);
        String ip = req.getRemoteAddr();
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) ip = fwd.split(",")[0].trim();
        return ResponseEntity.ok(actions.confirm(me, body == null ? null : body.token(), ip));
    }

    @PostMapping("/action/cancel")
    public ResponseEntity<?> cancelAction(@AuthenticationPrincipal CustomUserDetails me,
                                          @RequestBody TokenRequest body, HttpServletRequest req) {
        sameOrigin(req);
        actions.cancel(me.getUserId(), body == null ? null : body.token());
        return ResponseEntity.noContent().build();
    }

    /** NEW: PDF / Excel / Word / photo ko handle karta hai. true = handle ho gaya (purana text-file code skip). */
    private boolean readRich(MultipartFile f, String name, StringBuilder forAi, List<AiChatClient.Image> images) {
        String ext = AiFileReader.ext(name);
        String mime = switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> null;
        };
        try {
            if (mime != null) {
                if (f.getSize() > MAX_IMAGE_BYTES || images.size() >= MAX_IMAGES) {
                    forAi.append("(this image was skipped: images must be under 5 MB and at most " + MAX_IMAGES + " per message)");
                } else {
                    images.add(new AiChatClient.Image(mime, Base64.getEncoder().encodeToString(f.getBytes())));
                    forAi.append("(this is an image; its text is read separately)");
                }
                return true;
            }
            String content = reader.read(name, f.getContentType(), f.getBytes());
            if (content == null) return false;   // purana code sambhalega
            forAi.append("```\n").append(content).append("\n```");
            return true;
        } catch (IOException | RuntimeException e) {
            forAi.append("(this file could not be read)");
            return true;
        }
    }

    public record ChatRequest(Long conversationId, String message, String model) {}
    public record RenameRequest(String title) {}
    public record TruncateRequest(Long fromMessageId) {}

    /* ---------------- models ---------------- */

    @GetMapping("/models")
    public List<AiChatService.ModelOption> models(@AuthenticationPrincipal CustomUserDetails me) {
        return chat.modelOptions(me.getUserId());
    }

    /* ---------------- conversations ---------------- */

    @GetMapping("/conversations")
    public List<AiChatService.ConvView> conversations(@AuthenticationPrincipal CustomUserDetails me) {
        return chat.conversations(me.getUserId());
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<?> messages(@AuthenticationPrincipal CustomUserDetails me, @PathVariable Long id) {
        try {
            return ResponseEntity.ok(chat.messages(me.getUserId(), id));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/conversations/{id}/rename")
    public ResponseEntity<?> rename(@AuthenticationPrincipal CustomUserDetails me, @PathVariable Long id,
                                    @RequestBody RenameRequest body, HttpServletRequest req) {
        sameOrigin(req);
        try {
            chat.rename(me.getUserId(), id, body == null ? null : body.title());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/conversations/{id}/delete")
    public ResponseEntity<?> delete(@AuthenticationPrincipal CustomUserDetails me, @PathVariable Long id,
                                    HttpServletRequest req) {
        sameOrigin(req);
        try {
            chat.delete(me.getUserId(), id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** Edit / regenerate: is message se aage ka sab hata do. */
    @PostMapping("/conversations/{id}/truncate")
    public ResponseEntity<?> truncate(@AuthenticationPrincipal CustomUserDetails me, @PathVariable Long id,
                                      @RequestBody TruncateRequest body, HttpServletRequest req) {
        sameOrigin(req);
        if (body == null || body.fromMessageId() == null) {
            return error(HttpStatus.BAD_REQUEST, "Message id is required.");
        }
        try {
            chat.truncate(me.getUserId(), id, body.fromMessageId());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /* ---------------- chat ---------------- */

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@AuthenticationPrincipal CustomUserDetails me, @RequestBody ChatRequest body,
                                  HttpServletRequest req) {
        sameOrigin(req);
        String text = body == null || body.message() == null ? "" : body.message().trim();
        if (text.isEmpty()) return error(HttpStatus.BAD_REQUEST, "Message is required.");
        if (text.length() > MAX_MESSAGE) return error(HttpStatus.BAD_REQUEST, "Message is too long.");
        try {
            return ResponseEntity.ok(chat.chat(me.getUserId(), body.conversationId(), text, text, false, body.model()));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping(value = "/chat/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> chatUpload(@AuthenticationPrincipal CustomUserDetails me,
                                        @RequestParam(value = "conversationId", required = false) Long conversationId,
                                        @RequestParam(value = "message", required = false) String message,
                                        @RequestParam(value = "model", required = false) String model,
                                        @RequestParam(value = "files", required = false) List<MultipartFile> files,
                                        HttpServletRequest req) {
        sameOrigin(req);
        String text = message == null ? "" : message.trim();
        if (text.length() > MAX_MESSAGE) return error(HttpStatus.BAD_REQUEST, "Message is too long.");
        List<MultipartFile> list = files == null ? List.of() : files;
        if (list.size() > MAX_FILES) return error(HttpStatus.BAD_REQUEST, "Maximum " + MAX_FILES + " files allowed.");

        List<String> names = new ArrayList<>();
        List<AiChatClient.Image> images = new ArrayList<>();   // NEW
        StringBuilder forAi = new StringBuilder(text);
        for (MultipartFile f : list) {
            if (f == null || f.isEmpty()) continue;
            if (f.getSize() > MAX_FILE_BYTES) return error(HttpStatus.BAD_REQUEST, "A file is larger than 10 MB.");
            String name = cleanName(f.getOriginalFilename());
            names.add(name);
            forAi.append("\n\n[Attached file: ").append(name).append("]\n");
            if (!isTextFile(name, f.getContentType()) && readRich(f, name, forAi, images)) continue;   // NEW
            if (isTextFile(name, f.getContentType())) {
                try {
                    String content = new String(f.getBytes(), StandardCharsets.UTF_8);
                    if (content.length() > MAX_FILE_TEXT) {
                        content = content.substring(0, MAX_FILE_TEXT) + "\n...[file truncated]";
                    }
                    forAi.append("```\n").append(content).append("\n```");
                } catch (IOException e) {
                    forAi.append("(this file could not be read)");
                }
            } else {
                forAi.append("(the content of this file type cannot be read in this version; "
                        + "tell the user to paste the text or upload a txt/csv file)");
            }
        }
        if (text.isEmpty() && names.isEmpty()) return error(HttpStatus.BAD_REQUEST, "Message is required.");

        String display = text + (names.isEmpty() ? "" : (text.isEmpty() ? "" : "\n") + "[Attachments: " + String.join(", ", names) + "]");
        try {
            return ResponseEntity.ok(chat.chat(me.getUserId(), conversationId, display, forAi.toString(),
                    !names.isEmpty(), model, images));
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /* ---------------- schedules (abhi band) ---------------- */

    @GetMapping("/schedules")
    public List<Object> schedules() {
        return List.of();
    }

    @GetMapping("/schedules/{id}/runs")
    public List<Object> runs(@PathVariable Long id) {
        return List.of();
    }

    @PostMapping({"/schedules", "/schedules/{id}/update", "/schedules/{id}/toggle",
            "/schedules/{id}/delete", "/schedules/{id}/run"})
    public ResponseEntity<?> schedulesNotAvailable() {
        return error(HttpStatus.BAD_REQUEST, "Scheduled tasks are not available yet.");
    }

    /* ---------------- helpers ---------------- */

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg == null ? "Request failed." : msg));
    }

    private String cleanName(String n) {
        String s = n == null ? "file" : n.replace("\\", "/");
        s = s.substring(s.lastIndexOf('/') + 1).replaceAll("[\\r\\n\\[\\]]", "").trim();
        if (s.isEmpty()) s = "file";
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private boolean isTextFile(String name, String contentType) {
        if (contentType != null && contentType.toLowerCase().startsWith("text/")) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXT.contains(name.substring(dot + 1).toLowerCase());
    }

    /** Browser POST par Origin bhejta hai; wo isi site ka hona chahiye. */
    private void sameOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            String ref = req.getHeader("Referer");
            if (ref == null || ref.isBlank()) return;   // non-browser client
            origin = ref;
        }
        String auth;
        try {
            auth = URI.create(origin).getAuthority();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bad origin.");
        }
        if (auth == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bad origin.");
        String host = req.getHeader("Host");
        String fwd = req.getHeader("X-Forwarded-Host");
        if (auth.equalsIgnoreCase(host) || (fwd != null && auth.equalsIgnoreCase(fwd.split(",")[0].trim()))) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-site request blocked.");
    }
}