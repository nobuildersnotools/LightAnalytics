package org.carrotcraft.lightAnalytics.model;

/**
 * A point-in-time sample of proxy-wide state, taken on a fixed interval by the
 * {@code ResourceSampler}. Snapshots are the basis for time-series metrics such
 * as peak player counts, population over time, and CPU/RAM trends.
 *
 * <p>Note: {@code cpuProcessLoad}, {@code cpuSystemLoad}, {@code heapUsed} and
 * {@code heapMax} describe the <em>proxy JVM</em>, not backend Minecraft servers.
 *
 * @param timestamp      epoch milliseconds the sample was taken
 * @param playerCount    players connected to the proxy at sample time
 * @param cpuProcessLoad CPU load of the proxy JVM in [0.0, 1.0], or a negative value if unavailable
 * @param cpuSystemLoad  CPU load of the whole machine in [0.0, 1.0], or a negative value if unavailable
 * @param heapUsed       used heap memory in bytes
 * @param heapMax        maximum heap memory in bytes, or -1 if undefined
 */
public record Snapshot(
        long timestamp,
        int playerCount,
        double cpuProcessLoad,
        double cpuSystemLoad,
        long heapUsed,
        long heapMax
) {
}
