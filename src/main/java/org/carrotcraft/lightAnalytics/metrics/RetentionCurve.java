package org.carrotcraft.lightAnalytics.metrics;

/**
 * Retention curve for the new-player cohort first seen in a window: the fraction of
 * the cohort that came back at least 1, 7, and 30 days after each member's own first
 * login, plus the bounce rate (fraction that logged in only once, ever).
 *
 * <p>Rates are 0 when the cohort is empty. The curve is monotonically non-increasing
 * (d1 ≥ d7 ≥ d30) by construction.
 *
 * @param cohortSize   number of players first seen in the window
 * @param d1           fraction returning ≥ 1 day after their first login
 * @param d7           fraction returning ≥ 7 days after their first login
 * @param d30          fraction returning ≥ 30 days after their first login
 * @param bounceRate   fraction that logged in exactly once, ever
 */
public record RetentionCurve(int cohortSize, double d1, double d7, double d30, double bounceRate) {
}
