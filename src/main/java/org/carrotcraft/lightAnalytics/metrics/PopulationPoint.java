package org.carrotcraft.lightAnalytics.metrics;

/**
 * One point in a population-over-time series: the connected player count at a
 * sample instant. Series are built from full-resolution snapshots, so they are
 * intended for windows within the full-resolution retention period.
 *
 * @param timestamp   epoch milliseconds of the sample
 * @param playerCount players connected to the proxy at that instant
 */
public record PopulationPoint(long timestamp, int playerCount) {
}
