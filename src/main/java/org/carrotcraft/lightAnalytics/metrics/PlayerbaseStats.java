package org.carrotcraft.lightAnalytics.metrics;

/**
 * Windowed playerbase composition over a {@code [from, to]} range, derived from
 * the {@code sessions} and {@code players} tables.
 *
 * <p>These figures are exact only within the session-retention horizon (sessions
 * are pruned after {@code session-retention-days}); the dashboard windows
 * (24h/7d/30d) all sit comfortably inside the 90-day default.
 *
 * @param uniquePlayers     distinct players with at least one session in the window
 * @param newPlayers        players whose first-ever login fell in the window
 * @param returningPlayers  unique players who are not new ({@code uniquePlayers - newPlayers})
 * @param regularPlayers    players with at least {@code regularThreshold} sessions in the window
 * @param regularThreshold  the session-count threshold used to classify a "regular" player
 * @param totalJoins        total sessions (logins) begun in the window
 * @param avgJoinsPerPlayer mean sessions per unique player ({@code totalJoins / uniquePlayers})
 */
public record PlayerbaseStats(
        long uniquePlayers,
        long newPlayers,
        long returningPlayers,
        long regularPlayers,
        int regularThreshold,
        long totalJoins,
        double avgJoinsPerPlayer
) {
}
