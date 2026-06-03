package org.carrotcraft.lightAnalytics.metrics;

/**
 * Did new players stay? Of the cohort whose {@code first_seen} fell within a
 * window {@code W}, the fraction that started any further session strictly after
 * the end of {@code W}.
 *
 * @param cohortSize    number of players first seen during the window
 * @param retainedCount how many of them returned (any session after the window)
 * @param retentionRate {@code retainedCount / cohortSize}, or 0.0 for an empty cohort
 */
public record Retention(int cohortSize, int retainedCount, double retentionRate) {
}
