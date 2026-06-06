package org.carrotcraft.lightAnalytics.web;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small per-key token-bucket rate limiter, used to throttle login-token
 * redemption per client IP. Token guessing is already infeasible (256-bit
 * secrets), so this is defence-in-depth: it bounds the work a flood of bogus
 * {@code /auth} hits can impose.
 *
 * <p>Each key starts with {@code capacity} tokens and refills at
 * {@code refillPerSecond}; an attempt costs one token. Safe for concurrent use.
 */
public final class RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillMillis;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillMillis = now;
        }
    }

    private final double capacity;
    private final double refillPerSecond;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(double capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, Clock.systemUTC());
    }

    RateLimiter(double capacity, double refillPerSecond, Clock clock) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
    }

    /** Attempts to spend one token for {@code key}; returns true if allowed. */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            double refill = (now - bucket.lastRefillMillis) / 1000.0 * refillPerSecond;
            bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            bucket.lastRefillMillis = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
