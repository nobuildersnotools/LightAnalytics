package org.carrotcraft.lightAnalytics.metrics;

/**
 * One bar of the session-length distribution: a human label (e.g. {@code "5–15m"}),
 * the bucket's inclusive lower bound in milliseconds, and the count of counted
 * sessions whose duration fell in the bucket. The final bucket is open-ended, with
 * {@code upperMillis} of {@code -1}.
 */
public record DurationBucket(String label, long lowerMillis, long upperMillis, long count) {
}
