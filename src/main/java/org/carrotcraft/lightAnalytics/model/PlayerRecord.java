package org.carrotcraft.lightAnalytics.model;

import java.util.UUID;

/**
 * Aggregate, one-row-per-player record maintained across all of a player's
 * sessions. Underpins metrics such as unique players, new-player rate, and
 * retention ("do new players come back").
 *
 * @param uuid          the player's unique id
 * @param username      the player's name as of their most recent login
 * @param firstSeen     epoch milliseconds of the player's first ever login
 * @param lastSeen      epoch milliseconds of the player's most recent activity
 * @param totalSessions number of sessions this player has started
 */
public record PlayerRecord(
        UUID uuid,
        String username,
        long firstSeen,
        long lastSeen,
        int totalSessions
) {
}
