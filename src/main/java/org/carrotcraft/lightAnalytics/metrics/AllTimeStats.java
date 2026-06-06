package org.carrotcraft.lightAnalytics.metrics;

/**
 * Headline figures that span the entire history retained by the plugin, as shown
 * on the web dashboard. Unlike the windowed metrics, these are not bounded to a
 * {@code [from, to]} range.
 *
 * <p>{@code peakPlayers}/{@code peakAt} survive full-resolution compaction via the
 * hourly rollups, and {@code totalSessions} survives session pruning because it is
 * summed from the cumulative per-player counter rather than the prunable
 * {@code sessions} table. {@code uniquePlayers} and {@code firstEverSeen} come from
 * the never-pruned {@code players} table.
 *
 * @param currentPopulation present connected player count (latest snapshot)
 * @param peakPlayers       highest concurrent player count ever observed (0 if none)
 * @param peakAt            epoch milliseconds the all-time peak occurred, or -1 if none
 * @param uniquePlayers     distinct players ever seen
 * @param totalSessions     all-time login count
 * @param firstEverSeen     epoch milliseconds of the earliest first login, or -1 if none
 */
public record AllTimeStats(
        int currentPopulation,
        int peakPlayers,
        long peakAt,
        long uniquePlayers,
        long totalSessions,
        long firstEverSeen
) {
}
