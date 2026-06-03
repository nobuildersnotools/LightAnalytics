package org.carrotcraft.lightAnalytics.model;

import java.util.UUID;

/**
 * A single connection of a player to the proxy, from login to disconnect.
 * Opened on {@code PostLoginEvent} and finalized on {@code DisconnectEvent}.
 * Sessions are the basis for metrics such as concurrent population, average
 * playtime, and churn.
 *
 * @param id         database identifier of the session row
 * @param uuid       the player's unique id
 * @param username   the player's name at login time
 * @param server     the most recent backend server the player was on, or null if not yet connected
 * @param loginTime  epoch milliseconds the session began
 * @param logoutTime epoch milliseconds the session ended, or null while still open
 */
public record PlayerSession(
        long id,
        UUID uuid,
        String username,
        String server,
        long loginTime,
        Long logoutTime
) {
}
