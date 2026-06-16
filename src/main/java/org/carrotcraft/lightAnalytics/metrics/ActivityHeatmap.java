package org.carrotcraft.lightAnalytics.metrics;

/**
 * Login activity bucketed by day-of-week and hour-of-day (UTC), the data behind the
 * dashboard heatmap. {@code grid[day][hour]} holds the login count for that cell,
 * where {@code day} is 0 = Sunday through 6 = Saturday (matching SQLite's
 * {@code strftime('%w', ...)}) and {@code hour} is 0–23. {@code max} is the largest
 * single-cell count, so the frontend can scale colour intensity without a second pass.
 *
 * @param grid  7×24 login counts, indexed {@code [dayOfWeek][hour]}
 * @param max   the largest value in {@code grid} (0 when empty)
 */
public record ActivityHeatmap(long[][] grid, long max) {

    public static final int DAYS = 7;
    public static final int HOURS = 24;
}
