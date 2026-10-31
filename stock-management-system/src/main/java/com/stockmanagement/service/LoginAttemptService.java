package com.stockmanagement.service;

import com.stockmanagement.config.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login brute-force protection (in-memory; counters reset on restart and are per server).
 *
 * Three independent counters, so an attacker can neither guess passwords freely nor lock real users out cheaply:
 *  1. username + IP  : 5 wrong passwords  -> that user is blocked ONLY from that IP for 15 min.
 *                      (A stranger typing a victim's username from his own IP cannot lock the victim out.)
 *  2. IP             : 25 wrong passwords from one IP (any usernames) -> that IP blocked for 15 min.
 *                      (Stops password spraying across many usernames.)
 *                      Skipped for IPs listed in app.security.trusted-ips (e.g. the office), so one person's typos
 *                      cannot lock the whole office out. Counters 1 and 3 still apply to trusted IPs.
 *  3. username only  : 40 wrong passwords from many IPs -> username blocked for 15 min.
 *                      (Stops distributed guessing against one account; needs a large botnet to abuse.)
 * Listens to Spring Security login events, so the login handler itself is unchanged.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    public static final int MAX_ATTEMPTS = 5;          // per username + IP
    public static final int MAX_PER_IP = 25;           // per IP, any usernames
    public static final int MAX_PER_USER = 40;         // per username, any IPs
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final int PURGE_THRESHOLD = 5000;
    private static final int HARD_CAP = 200_000;       // never let the map grow past this
    private static final int MAX_NAME_LEN = 100;

    private static final class Attempt {
        int count;
        Instant lastFailure;
        Instant lockedUntil;
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final boolean trustProxy;
    private final int trustedHops;
    private final Set<String> trustedIps;

    public LoginAttemptService(
            @Value("${app.security.trust-proxy:${api.external.trust-proxy:false}}") boolean trustProxy,
            @Value("${app.security.trusted-proxy-hops:${api.external.trusted-proxy-hops:1}}") int trustedHops,
            @Value("${app.security.trusted-ips:}") String trustedIps) {
        this.trustProxy = trustProxy;
        this.trustedHops = Math.max(1, trustedHops);
        this.trustedIps = ClientIp.parseIpList(trustedIps);
        if (!this.trustedIps.isEmpty()) log.info("Trusted IPs (no per-IP login lockout): {}", this.trustedIps);
    }

    /** Real client IP for this request (X-Forwarded-For only trusted behind our own proxy). */
    public String clientIp(HttpServletRequest request) {
        return ClientIp.resolve(request, trustProxy, trustedHops);
    }

    public boolean isLocked(String username, String ip) {
        return locked("p:" + key(username) + "|" + ip)
                | (!ClientIp.isTrusted(trustedIps, ip) && locked("i:" + ip))
                | locked("u:" + key(username));
    }

    public void recordFailure(String username, String ip) {
        Instant now = Instant.now();
        String u = key(username);
        bump("p:" + u + "|" + ip, MAX_ATTEMPTS, now);
        if (!ClientIp.isTrusted(trustedIps, ip)) bump("i:" + ip, MAX_PER_IP, now);
        bump("u:" + u, MAX_PER_USER, now);
        purgeIfLarge(now);
    }

    public void recordSuccess(String username, String ip) {
        attempts.remove("p:" + key(username) + "|" + ip);
        attempts.remove("u:" + key(username));
    }

    // ---- Spring Security login events ----

    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        recordFailure(event.getAuthentication().getName(), currentIp());
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        recordSuccess(event.getAuthentication().getName(), currentIp());
    }

    // ---- helpers ----

    private String currentIp() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes sra) {
            return clientIp(sra.getRequest());
        }
        return "unknown";
    }

    private boolean locked(String k) {
        Attempt a = attempts.get(k);
        if (a == null || a.lockedUntil == null) return false;
        if (Instant.now().isBefore(a.lockedUntil)) return true;
        attempts.remove(k, a);   // lock expired
        return false;
    }

    private void bump(String k, int max, Instant now) {
        if (!attempts.containsKey(k) && attempts.size() >= HARD_CAP) return;   // memory guard under flood
        attempts.compute(k, (kk, existing) -> {
            Attempt a = existing == null ? new Attempt() : existing;
            if (a.lockedUntil != null && now.isBefore(a.lockedUntil)) return a;   // already locked

            if (a.lastFailure != null && now.isAfter(a.lastFailure.plus(ATTEMPT_WINDOW))) {
                a.count = 0;   // forget old failures
            }
            a.count++;
            a.lastFailure = now;
            if (a.count >= max) {
                a.lockedUntil = now.plus(LOCK_DURATION);
                a.count = 0;
                log.warn("Login blocked for {} minutes after {} failed attempts: {}",
                        LOCK_DURATION.toMinutes(), max, kk);
            }
            return a;
        });
    }

    private void purgeIfLarge(Instant now) {
        if (attempts.size() <= PURGE_THRESHOLD) return;
        attempts.entrySet().removeIf(e -> {
            Attempt a = e.getValue();
            boolean lockExpired = a.lockedUntil != null && now.isAfter(a.lockedUntil);
            boolean stale = a.lockedUntil == null && a.lastFailure != null
                    && now.isAfter(a.lastFailure.plus(ATTEMPT_WINDOW));
            return lockExpired || stale;
        });
    }

    private static String key(String username) {
        String s = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return s.length() > MAX_NAME_LEN ? s.substring(0, MAX_NAME_LEN) : s;
    }
}