package com.stockmanagement.apikey;

import com.stockmanagement.service.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bahar ke darwaze (/api/v1/**) ka chowkidar. Sirf ApiKeySecurityConfig ki chain me lagta hai
 * (jaan-boojh kar @Component nahi hai, taaki baaki app par kabhi na chale).
 *
 * Har request par:
 *  1. Master switch band (api.external.enabled=false) = 404, jaise darwaza hai hi nahi.
 *  2. HTTPS zaruri (localhost chhod kar).
 *  3. Key URL me aayi to reject (URL logs / history me chali jaati hai). Key sirf header me:
 *     "Authorization: Bearer sf_live_..." ya "X-API-Key: sf_live_...".
 *  4. Har IP par request limit, aur galat key baar-baar = IP thodi der ke liye block.
 *  5. ApiKeyService.verify: hash, delete, expiry, user ACTIVE, company, allowed IP.
 *  6. Har key par request limit.
 *  7. Sahi ho to us user ko (CustomUserDetails) is ek request ke liye set karta hai. Session / cookie nahi banti.
 * Galat hone par sirf chhota JSON error; karan (expire / delete / IP) bahar nahi batate.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** Controller is attribute se jaanta hai ki kis key se request aayi. */
    public static final String ATTR_KEY = "stockflow.apiKey";
    /** Client ka IP (trust-proxy setting ke hisaab se) audit ke liye. */
    public static final String ATTR_IP = "stockflow.apiKeyIp";

    private static final long MINUTE = 60_000L;
    private static final long FAIL_WINDOW = 15 * 60_000L;

    private final ApiKeyService keys;
    private final boolean enabled;
    private final boolean requireHttps;
    private final boolean trustProxy;
    private final int perKeyPerMinute;
    private final int perIpPerMinute;
    private final int maxFailsPerIp;

    private final Map<String, Deque<Long>> ipHits = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> ipFails = new ConcurrentHashMap<>();
    private final Map<Long, Deque<Long>> keyHits = new ConcurrentHashMap<>();
    private volatile long lastSweep = System.currentTimeMillis();

    public ApiKeyAuthFilter(ApiKeyService keys, boolean enabled, boolean requireHttps, boolean trustProxy,
                            int perKeyPerMinute, int perIpPerMinute, int maxFailsPerIp) {
        this.keys = keys;
        this.enabled = enabled;
        this.requireHttps = requireHttps;
        this.trustProxy = trustProxy;
        this.perKeyPerMinute = Math.max(1, perKeyPerMinute);
        this.perIpPerMinute = Math.max(1, perIpPerMinute);
        this.maxFailsPerIp = Math.max(1, maxFailsPerIp);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("Cache-Control", "no-store");
        sweep();

        if (!enabled) { error(res, 404, "Not found."); return; }

        String ip = clientIp(req);
        if (requireHttps && !isHttps(req) && !isLoopback(req.getRemoteAddr())) {
            error(res, 403, "HTTPS is required."); return;
        }
        String q = req.getQueryString();
        if (q != null && (q.toLowerCase(Locale.ROOT).contains("key") || q.contains(ApiKeyService.PREFIX))) {
            error(res, 400, "Send the API key only in the Authorization header, never in the URL."); return;
        }
        if (count(ipFails, ip, FAIL_WINDOW, false) >= maxFailsPerIp) {
            error(res, 429, "Too many failed attempts. Try again later."); return;
        }
        if (count(ipHits, ip, MINUTE, true) > perIpPerMinute) {
            error(res, 429, "Too many requests. Please slow down."); return;
        }

        String raw = readKey(req);
        Optional<ApiKeyService.Verified> v = raw == null ? Optional.empty() : keys.verify(raw, ip);
        if (v.isEmpty()) {
            count(ipFails, ip, FAIL_WINDOW, true);
            log.warn("API request rejected (bad or unusable key) from IP {} {} {}", ip, req.getMethod(), req.getRequestURI());
            res.setHeader("WWW-Authenticate", "Bearer");
            error(res, 401, "Unauthorized."); return;
        }
        ApiKey key = v.get().key();
        if (count(keyHits, key.getId(), MINUTE, true) > perKeyPerMinute) {
            error(res, 429, "Too many requests for this API key. Please slow down."); return;
        }

        CustomUserDetails principal = new CustomUserDetails(v.get().user());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(ctx);
        req.setAttribute(ATTR_KEY, key);
        req.setAttribute(ATTR_IP, ip);
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /* ---------------- helpers ---------------- */

    private static String readKey(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String k = h.substring(7).trim();
            return k.isEmpty() ? null : k;
        }
        String x = req.getHeader("X-API-Key");
        return x == null || x.isBlank() ? null : x.trim();
    }

    /** trustProxy=false (default): seedha connection ka IP (X-Forwarded-For se koi IP jhooth nahi bol sakta). */
    private String clientIp(HttpServletRequest req) {
        if (trustProxy) {
            String fwd = req.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private boolean isHttps(HttpServletRequest req) {
        if (req.isSecure()) return true;
        return trustProxy && "https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto"));
    }

    private static boolean isLoopback(String addr) {
        return addr != null && (addr.equals("127.0.0.1") || addr.equals("::1") || addr.equals("0:0:0:0:0:0:0:1"));
    }

    /** Sliding window: window ke andar kitne. add=true ho to pehle ek naya hit jodta hai. */
    private static <K> int count(Map<K, Deque<Long>> map, K k, long window, boolean add) {
        long now = System.currentTimeMillis();
        Deque<Long> d = map.computeIfAbsent(k, x -> new ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst() < now - window) d.removeFirst();
            if (add) d.addLast(now);
            return d.size();
        }
    }

    /** Purani khali entries hatao taaki memory na bhare. */
    private void sweep() {
        long now = System.currentTimeMillis();
        if (now - lastSweep < 5 * MINUTE) return;
        lastSweep = now;
        clean(ipHits, now - MINUTE);
        clean(ipFails, now - FAIL_WINDOW);
        clean(keyHits, now - MINUTE);
    }

    private static <K> void clean(Map<K, Deque<Long>> map, long before) {
        map.entrySet().removeIf(e -> {
            Deque<Long> d = e.getValue();
            synchronized (d) {
                while (!d.isEmpty() && d.peekFirst() < before) d.removeFirst();
                return d.isEmpty();
            }
        });
    }

    private static void error(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }
}