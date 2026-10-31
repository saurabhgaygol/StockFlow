package com.stockmanagement.config;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory sliding-window rate limiter (per key, e.g. per user id).
 * Counters live in this server's memory only and reset on restart.
 */
public final class SlidingWindowLimiter {

    private static final int SWEEP_THRESHOLD = 10_000;

    private final int max;
    private final long windowMs;
    private final ConcurrentHashMap<Object, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int max, long windowMs) {
        this.max = Math.max(1, max);
        this.windowMs = Math.max(1000, windowMs);
    }

    /** Returns true and records the hit if the key is under its limit; false if it must wait. */
    public boolean tryAcquire(Object key) {
        long now = System.currentTimeMillis();
        if (hits.size() > SWEEP_THRESHOLD) sweep(now);
        ArrayDeque<Long> d = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst() < now - windowMs) d.removeFirst();
            if (d.size() >= max) return false;
            d.addLast(now);
            return true;
        }
    }

    private void sweep(long now) {
        hits.entrySet().removeIf(e -> {
            ArrayDeque<Long> d = e.getValue();
            synchronized (d) {
                while (!d.isEmpty() && d.peekFirst() < now - windowMs) d.removeFirst();
                return d.isEmpty();
            }
        });
    }
}