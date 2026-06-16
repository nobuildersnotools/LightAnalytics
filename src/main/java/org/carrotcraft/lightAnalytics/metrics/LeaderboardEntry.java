package org.carrotcraft.lightAnalytics.metrics;

/**
 * One row of the playtime leaderboard: a player's counted playtime and session count
 * over the selected window. Username is the player's current canonical name.
 */
public record LeaderboardEntry(String username, long sessions, long playtimeMillis) {
}
