package com.stockmanagement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Galat password par username lock (in-memory). Server restart par counter reset ho jata hai.
 * Spring ke login events sunta hai, isliye login handler me kuch badalna nahi padta.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    public static final int MAX_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final int PURGE_THRESHOLD = 5000;

    private static final class Attempt {
        int count;
        Instant lastFailure;
        Instant lockedUntil;
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public boolean isLocked(String username) {
        String key = key(username);
        Attempt a = attempts.get(key);
        if (a == null || a.lockedUntil == null) return false;
        if (Instant.now().isBefore(a.lockedUntil)) return true;
        attempts.remove(key, a);   // lock khatam
        return false;
    }

    public void recordFailure(String username) {
        Instant now = Instant.now();
        attempts.compute(key(username), (k, existing) -> {
            Attempt a = existing == null ? new Attempt() : existing;
            if (a.lockedUntil != null && now.isBefore(a.lockedUntil)) return a;   // already locked

            if (a.lastFailure != null && now.isAfter(a.lastFailure.plus(ATTEMPT_WINDOW))) {
                a.count = 0;   // purani galat attempts bhool jao
            }
            a.count++;
            a.lastFailure = now;
            if (a.count >= MAX_ATTEMPTS) {
                a.lockedUntil = now.plus(LOCK_DURATION);
                a.count = 0;
                log.warn("Login locked for {} minutes after {} failed attempts: {}",
                        LOCK_DURATION.toMinutes(), MAX_ATTEMPTS, k);
            }
            return a;
        });
        purgeIfLarge(now);
    }

    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    // ---- Spring Security login events ----

    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        recordFailure(event.getAuthentication().getName());
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        recordSuccess(event.getAuthentication().getName());
    }

    // ---- helpers ----

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
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}