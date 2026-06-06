package org.carrotcraft.lightAnalytics.web;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A tiny per-key TTL cache that sits between the HTTP handlers and
 * {@link org.carrotcraft.lightAnalytics.metrics.MetricsService}. Because every
 * metric read is serialised onto the single database thread, this collapses
 * bursts of concurrent dashboard requests into one query per key per TTL, keeping
 * the collection and retention tasks from contending with the web layer.
 *
 * <p>A per-key lock ensures only one thread recomputes a given key on a miss; the
 * rest wait and reuse the fresh value rather than piling onto the database thread.
 */
public final class MetricsCache {

    private record Entry(long expiresAt, Object value) {
    }

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public MetricsCache() {
        this(Clock.systemUTC());
    }

    MetricsCache(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns the cached value for {@code key} if still fresh, otherwise computes it
     * via {@code loader}, stores it for {@code ttlMillis}, and returns it.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, long ttlMillis, Supplier<T> loader) {
        Entry cached = entries.get(key);
        if (cached != null && clock.millis() < cached.expiresAt()) {
            return (T) cached.value();
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            cached = entries.get(key);
            if (cached != null && clock.millis() < cached.expiresAt()) {
                return (T) cached.value();
            }
            T value = loader.get();
            entries.put(key, new Entry(clock.millis() + ttlMillis, value));
            return value;
        }
    }
}
