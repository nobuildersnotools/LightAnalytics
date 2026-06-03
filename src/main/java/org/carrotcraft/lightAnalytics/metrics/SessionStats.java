package org.carrotcraft.lightAnalytics.metrics;

/**
 * Aggregate playtime over sessions that began in a window. Open sessions (no
 * logout yet) and sub-second "ghost" sessions (e.g. 14ms connection blips that
 * never reach a backend) are excluded from the averages and reported separately.
 *
 * @param countedSessions      closed, non-ghost sessions contributing to the stats
 * @param averageDurationMillis mean duration of the counted sessions, or 0.0 if none
 * @param totalPlaytimeMillis  summed duration of the counted sessions
 * @param ghostSessionsIgnored closed sessions shorter than the ghost threshold
 * @param openSessionsIgnored  sessions still open (no logout time) at query time
 */
public record SessionStats(
        long countedSessions,
        double averageDurationMillis,
        long totalPlaytimeMillis,
        long ghostSessionsIgnored,
        long openSessionsIgnored
) {
}
