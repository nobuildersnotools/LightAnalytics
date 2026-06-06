package org.carrotcraft.lightAnalytics.metrics;

/**
 * One point on the dashboard time-series chart. Produced by
 * {@link MetricsService#series} by merging full-resolution snapshots with hourly
 * rollups and bucket-averaging down to a bounded number of points, so a single
 * point may represent either an exact sample or an aggregate of several.
 *
 * <p>CPU loads are fractions in {@code [0.0, 1.0]} (or negative when the JVM could
 * not report them); heap figures are bytes.
 *
 * @param timestamp   epoch milliseconds at the point (bucket start when aggregated)
 * @param playerCount average online player count over the point
 * @param cpuProcess  average proxy-JVM CPU load
 * @param cpuSystem   average machine-wide CPU load
 * @param heapUsed    average used heap, bytes
 * @param heapMax     maximum heap over the point, bytes (-1 if undefined)
 */
public record SeriesPoint(
        long timestamp,
        double playerCount,
        double cpuProcess,
        double cpuSystem,
        double heapUsed,
        long heapMax
) {
}
