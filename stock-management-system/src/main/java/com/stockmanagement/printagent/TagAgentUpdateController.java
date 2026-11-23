package com.stockmanagement.printagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Automatic updates of the Printer Agent. The files are the ones built into the application:
 *   src/main/resources/agent/stockflow-printer-agent.exe   the program
 *   src/main/resources/agent/agent.json                    {version, sha256, size, signature} made by the release tool
 * The server only hands them out; the AGENT checks the signature against the release key built into it, so this server
 * (or anyone who got into it) cannot make a shop computer run an unsigned program. If agent.json is missing, or does
 * not match the exe (someone forgot to sign the new build), nothing is offered.
 * Only a paired agent can call these (TagAgentSecurityConfig).
 */
@RestController
public class TagAgentUpdateController {

    private static final Logger log = LoggerFactory.getLogger(TagAgentUpdateController.class);

    private record Release(Map<String, Object> manifest, byte[] exe) {}

    private final ObjectMapper mapper;
    private volatile Release cached;
    private volatile boolean loaded;

    public TagAgentUpdateController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private Release release() {
        if (loaded) return cached;
        synchronized (this) {
            if (loaded) return cached;
            cached = load();
            loaded = true;
            return cached;
        }
    }

    private Release load() {
        try (InputStream exeIn = getClass().getResourceAsStream("/agent/stockflow-printer-agent.exe");
             InputStream jsonIn = getClass().getResourceAsStream("/agent/agent.json")) {
            if (exeIn == null || jsonIn == null) return null;
            byte[] exe = exeIn.readAllBytes();
            @SuppressWarnings("unchecked")
            Map<String, Object> m = mapper.readValue(jsonIn.readAllBytes(), Map.class);
            String version = String.valueOf(m.get("version"));
            String sha = String.valueOf(m.get("sha256"));
            String sig = String.valueOf(m.get("signature"));
            if (!version.matches("\\d+\\.\\d+\\.\\d+") || sig.isBlank() || "null".equals(sig)) {
                log.error("agent.json is not valid; agent updates are not offered");
                return null;
            }
            byte[] d = MessageDigest.getInstance("SHA-256").digest(exe);
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            if (!hex.toString().equalsIgnoreCase(sha)) {
                log.error("agent.json does not match stockflow-printer-agent.exe (sign the new build); agent updates are not offered");
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("version", version);
            out.put("sha256", hex.toString());
            out.put("size", exe.length);
            out.put("signature", sig);
            return new Release(out, exe);
        } catch (Exception e) {
            log.error("Could not read the agent release files", e);
            return null;
        }
    }

    /** the newest version (the agent decides whether it is newer than itself) */
    @GetMapping("/api/tag-agent/update")
    public ResponseEntity<Map<String, Object>> update() {
        Release r = release();
        if (r == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r.manifest());
    }

    @GetMapping("/api/tag-agent/update/file")
    public ResponseEntity<byte[]> file() {
        Release r = release();
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore()).body(r.exe());
    }
}