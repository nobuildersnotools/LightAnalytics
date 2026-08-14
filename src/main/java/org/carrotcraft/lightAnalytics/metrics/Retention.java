package org.carrotcraft.lightAnalytics.metrics;

/**
 * Did new players stay? Of the cohort whose {@code first_seen} fell within a
 * window {@code W}, the fraction that started at least one further session after
 * their own first login.
 *
 * <p>The return is measured per player from their first sight rather than from the
 * end of {@code W}: {@code W} normally ends at the present moment, so "returned
 * after the window" can never be satisfied and would peg the rate at zero.
 *
 * @param cohortSize    number of players first seen during the window
 * @param retainedCount how many of them came back for a later session
 * @param retentionRate {@code retainedCount / cohortSize}, or 0.0 for an empty cohort
 */
public record Retention(int cohortSize, int retainedCount, double retentionRate) {
}
