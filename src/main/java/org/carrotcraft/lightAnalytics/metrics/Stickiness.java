package org.carrotcraft.lightAnalytics.metrics;

/**
 * Engagement stickiness: distinct active players over the trailing day, week, and
 * month, plus the DAU/MAU ratio (the conventional stickiness figure — what fraction
 * of a month's players show up on a given day). These are anchored to "now" and do
 * not depend on the dashboard's selected window.
 *
 * @param dau          distinct players active in the last 24 h
 * @param wau          distinct players active in the last 7 d
 * @param mau          distinct players active in the last 30 d
 * @param stickiness   {@code dau / mau} (0 when {@code mau} is 0)
 */
public record Stickiness(long dau, long wau, long mau, double stickiness) {
}
