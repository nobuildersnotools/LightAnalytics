package org.carrotcraft.lightAnalytics.metrics;

/**
 * Average and peak resource usage over a window, recombined across full-resolution
 * snapshots and any hourly rollups the window spans (averages are sample-count
 * weighted; peaks are the max of both resolutions).
 *
 * <p><strong>All figures describe the proxy JVM only</strong>, never backend
 * Minecraft servers. CPU loads are in {@code [0.0, 1.0]}; a negative average can
 * only appear if every sample reported CPU as unavailable.
 *
 * @param cpuProcessAvg mean proxy-JVM CPU load
 * @param cpuProcessPeak peak proxy-JVM CPU load
 * @param cpuSystemAvg  mean machine-wide CPU load
 * @param cpuSystemPeak peak machine-wide CPU load
 * @param heapUsedAvg   mean used heap, bytes
 * @param heapUsedPeak  peak used heap, bytes
 * @param heapMax       maximum heap, bytes (effectively constant for the JVM)
 * @param sampleCount   number of underlying samples behind the averages
 */
public record ResourceTrends(
        double cpuProcessAvg,
        double cpuProcessPeak,
        double cpuSystemAvg,
        double cpuSystemPeak,
        double heapUsedAvg,
        long heapUsedPeak,
        long heapMax,
        long sampleCount
) {
}
