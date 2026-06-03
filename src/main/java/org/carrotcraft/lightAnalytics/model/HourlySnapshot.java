package org.carrotcraft.lightAnalytics.model;

/**
 * A downsampled, one-row-per-hour rollup of {@link Snapshot}s, produced by the
 * retention/compaction pass once full-resolution snapshots age out of their
 * recent window. Min/max preserve long-range extremes (e.g. peak population);
 * the averages, together with {@code sampleCount}, let a window that spans both
 * full-resolution and hourly data be recombined with exact sample-count
 * weighting.
 *
 * <p>As with {@link Snapshot}, the CPU and heap figures describe the
 * <em>proxy JVM</em>, never backend Minecraft servers.
 *
 * @param bucketStart    hour-aligned epoch milliseconds ({@code timestamp - timestamp % 3_600_000})
 * @param sampleCount    number of full-resolution snapshots rolled into this bucket
 * @param playerCountMin lowest player count observed in the bucket
 * @param playerCountMax highest player count observed in the bucket
 * @param playerCountAvg mean player count over the bucket
 * @param cpuProcessAvg  mean proxy-JVM CPU load over the bucket
 * @param cpuProcessMax  peak proxy-JVM CPU load over the bucket
 * @param cpuSystemAvg   mean machine-wide CPU load over the bucket
 * @param cpuSystemMax   peak machine-wide CPU load over the bucket
 * @param heapUsedAvg    mean used heap (bytes) over the bucket
 * @param heapUsedMax    peak used heap (bytes) over the bucket
 * @param heapMax        maximum heap (bytes); effectively constant for the JVM
 */
public record HourlySnapshot(
        long bucketStart,
        int sampleCount,
        int playerCountMin,
        int playerCountMax,
        double playerCountAvg,
        double cpuProcessAvg,
        double cpuProcessMax,
        double cpuSystemAvg,
        double cpuSystemMax,
        double heapUsedAvg,
        long heapUsedMax,
        long heapMax
) {
}
