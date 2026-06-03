package org.carrotcraft.lightAnalytics.metrics;

/**
 * The highest concurrent player count observed in a window, and when it occurred.
 * When the peak comes from a downsampled hourly bucket, {@code atTimestamp} is the
 * start of that hour rather than an exact sample time.
 *
 * @param peak        highest player count seen in the window (0 if no data)
 * @param atTimestamp epoch milliseconds the peak occurred, or -1 if no data
 */
public record PeakPlayers(int peak, long atTimestamp) {
}
