package org.carrotcraft.lightAnalytics.metrics;

import java.util.List;

/**
 * Per-backend-server view for the dashboard's Servers page: who is connected to each
 * backend right now, and each backend's share of activity over the selected window.
 *
 * @param current   players connected to each server now, busiest first
 * @param window    each server's session/player/playtime activity over the window, by playtime
 */
public record ServersReport(List<ServerPresence> current, List<ServerActivity> window) {

    /** A backend server and the number of players connected to it at this moment. */
    public record ServerPresence(String server, int online) {
    }

    /** A backend server's activity over the window. */
    public record ServerActivity(String server, long sessions, long uniquePlayers, long playtimeMillis) {
    }
}
