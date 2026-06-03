package org.carrotcraft.lightAnalytics.metrics;

/**
 * Population trend for a window compared against the immediately preceding window
 * of equal length: average concurrent players now versus then.
 *
 * @param currentAvg      mean player count over the current window
 * @param previousAvg     mean player count over the preceding window of equal length
 * @param absoluteChange  {@code currentAvg - previousAvg}
 * @param percentChange   change relative to the previous window in [-1.0, ...], or
 *                        0.0 when the previous window had no data
 */
public record PopulationChange(
        double currentAvg,
        double previousAvg,
        double absoluteChange,
        double percentChange
) {
}
