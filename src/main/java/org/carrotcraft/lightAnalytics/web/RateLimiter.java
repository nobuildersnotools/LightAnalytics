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
 *
 * <p>The per-key map is bounded: a bucket that has refilled back to full carries
 * no state worth keeping, so once the map exceeds {@link #MAX_BUCKETS} those idle
 * buckets are swept. This keeps a flood of distinct client IPs (the very thing the
 * limiter defends against) from growing the map without bound.
 */
public final class RateLimiter {

    /** Soft cap on tracked keys; once exceeded, fully-refilled (idle) buckets are dropped. */
    private static final int MAX_BUCKETS = 10_000;

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
        if (buckets.size() > MAX_BUCKETS) {
            evictIdle(now);
        }
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

    /**
     * Drops buckets that, given the time elapsed since their last use, would have
     * refilled to full capacity. Such a bucket is indistinguishable from a freshly
     * created one, so removing it loses no rate-limiting state. A racing
     * {@code tryAcquire} simply recreates a full bucket for that key.
     */
    private void evictIdle(long now) {
        buckets.values().removeIf(bucket -> {
            synchronized (bucket) {
                double refill = (now - bucket.lastRefillMillis) / 1000.0 * refillPerSecond;
                return bucket.tokens + refill >= capacity;
            }
        });
    }
}
