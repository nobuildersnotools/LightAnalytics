package org.carrotcraft.lightAnalytics.command;

import org.carrotcraft.lightAnalytics.metrics.PeakPlayers;
import org.carrotcraft.lightAnalytics.metrics.PopulationChange;
import org.carrotcraft.lightAnalytics.metrics.ResourceTrends;
import org.carrotcraft.lightAnalytics.metrics.Retention;
import org.carrotcraft.lightAnalytics.metrics.SessionStats;

/** Snapshot of every metric rendered by {@code /lightanalytics summary}. */
public record SummaryReport(
        SummaryWindow window,
        long fromMillis,
        long toMillis,
        int currentPopulation,
        PeakPlayers peakPlayers,
        PopulationChange populationChange,
        int newPlayerCount,
        Retention retention,
        SessionStats sessionStats,
        ResourceTrends resourceTrends
) {
}
