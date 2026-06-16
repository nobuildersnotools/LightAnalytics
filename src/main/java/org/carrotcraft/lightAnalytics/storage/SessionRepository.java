package org.carrotcraft.lightAnalytics.storage;

import org.carrotcraft.lightAnalytics.model.PlayerSession;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persists and retrieves {@link PlayerSession}s. A session is opened on login,
 * has its backend server stamped as the player moves, and is closed on
 * disconnect. The {@link #between} accessor feeds future concurrency, playtime,
 * and churn metrics.
 *
 * <p>All methods take the {@link Connection} supplied by {@link Database}; they
 * are expected to run on the database executor thread.
 */
public final class SessionRepository {

    /** Inserts a new open session and returns its generated id. */
    public long open(Connection connection, UUID uuid, String username, String server, long loginTime)
            throws SQLException {
        String sql = "INSERT INTO sessions (uuid, username, server, login_time) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, server);
            ps.setLong(4, loginTime);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        }
    }

    /** Stamps the most recent backend server onto an open session. */
    public void updateServer(Connection connection, long sessionId, String server) throws SQLException {
        String sql = "UPDATE sessions SET server = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, server);
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        }
    }

    /** Finalizes a session by recording its logout time. */
    public void close(Connection connection, long sessionId, long logoutTime) throws SQLException {
        String sql = "UPDATE sessions SET logout_time = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, logoutTime);
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        }
    }

    /**
     * Closes every still-open session at the given time. Used on proxy shutdown
     * so sessions are not left dangling without a logout time.
     *
     * @return the number of sessions finalized
     */
    public int closeAllOpen(Connection connection, long logoutTime) throws SQLException {
        String sql = "UPDATE sessions SET logout_time = ? WHERE logout_time IS NULL";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, logoutTime);
            return ps.executeUpdate();
        }
    }

    /** Playtime aggregate over sessions begun in a window; see {@link #playtimeStats}. */
    public record Playtime(long countedSessions, long openSessions, long ghostSessions, long totalPlaytimeMillis) {
    }

    /**
     * Aggregates playtime over sessions that began within {@code [fromMillis, toMillis]},
     * computed in SQL so the metrics layer never materializes the session rows. A closed
     * session shorter than {@code minMillis} is a ghost (excluded); a session with no
     * logout is still open (excluded). Counted playtime sums only the closed,
     * non-ghost sessions.
     */
    public Playtime playtimeStats(Connection connection, long fromMillis, long toMillis, long minMillis)
            throws SQLException {
        String sql = "SELECT "
                + "COALESCE(SUM(CASE WHEN logout_time IS NOT NULL AND logout_time - login_time >= ? "
                + "THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN logout_time IS NULL THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN logout_time IS NOT NULL AND logout_time - login_time < ? "
                + "THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN logout_time IS NOT NULL AND logout_time - login_time >= ? "
                + "THEN logout_time - login_time ELSE 0 END), 0) "
                + "FROM sessions WHERE login_time BETWEEN ? AND ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, minMillis);
            ps.setLong(2, minMillis);
            ps.setLong(3, minMillis);
            ps.setLong(4, fromMillis);
            ps.setLong(5, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Playtime(0, 0, 0, 0);
                }
                return new Playtime(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
            }
        }
    }

    /** Sessions that began within {@code [fromMillis, toMillis]}, oldest first. */
    public List<PlayerSession> between(Connection connection, long fromMillis, long toMillis) throws SQLException {
        String sql = "SELECT id, uuid, username, server, login_time, logout_time "
                + "FROM sessions WHERE login_time BETWEEN ? AND ? ORDER BY login_time ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromMillis);
            ps.setLong(2, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                List<PlayerSession> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(map(rs));
                }
                return results;
            }
        }
    }

    /**
     * Count of distinct players with at least one session that began within
     * {@code [fromMillis, toMillis]}. Computed in SQL rather than by materializing
     * rows because a wide window can span tens of thousands of sessions.
     */
    public long uniquePlayersBetween(Connection connection, long fromMillis, long toMillis) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT uuid) FROM sessions WHERE login_time BETWEEN ? AND ?";
        return scalarBetween(connection, sql, fromMillis, toMillis);
    }

    /** Total number of sessions (logins) that began within {@code [fromMillis, toMillis]}. */
    public long joinsBetween(Connection connection, long fromMillis, long toMillis) throws SQLException {
        String sql = "SELECT COUNT(*) FROM sessions WHERE login_time BETWEEN ? AND ?";
        return scalarBetween(connection, sql, fromMillis, toMillis);
    }

    /**
     * Count of players who began at least {@code minSessions} sessions within
     * {@code [fromMillis, toMillis]} — the dashboard's "regular players" figure.
     * Grouping and counting happen in SQL so the metrics layer never holds the
     * per-player tallies.
     */
    public long regularPlayersBetween(Connection connection, long fromMillis, long toMillis, int minSessions)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM ("
                + "SELECT uuid FROM sessions WHERE login_time BETWEEN ? AND ? "
                + "GROUP BY uuid HAVING COUNT(*) >= ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromMillis);
            ps.setLong(2, toMillis);
            ps.setInt(3, minSessions);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** One backend server's share of activity over a window; see {@link #serverBreakdown}. */
    public record ServerRow(String server, long sessions, long uniquePlayers, long playtimeMillis) {
    }

    /** A backend server and how many players are currently connected to it; see {@link #currentByServer}. */
    public record PresenceRow(String server, int online) {
    }

    /** One player's activity over a window, ordered by playtime; see {@link #leaderboard}. */
    public record LeaderRow(String username, long sessions, long playtimeMillis) {
    }

    /** One {@code (dayOfWeek, hour)} cell of login activity; see {@link #activityByHour}. */
    public record HourCell(int dayOfWeek, int hour, long count) {
    }

    /**
     * Per-backend-server activity over sessions that began within {@code [fromMillis, toMillis]}:
     * session count, distinct players, and counted (closed, non-ghost) playtime, ordered by
     * playtime descending. A null server (the player never reached a backend) is bucketed under
     * {@code "(none)"}. Aggregated in SQL so the metrics layer never holds the per-server rows.
     */
    public List<ServerRow> serverBreakdown(Connection connection, long fromMillis, long toMillis, long minMillis)
            throws SQLException {
        String sql = "SELECT COALESCE(server, '(none)') AS srv, "
                + "COUNT(*) AS sessions, "
                + "COUNT(DISTINCT uuid) AS players, "
                + "COALESCE(SUM(CASE WHEN logout_time IS NOT NULL AND logout_time - login_time >= ? "
                + "THEN logout_time - login_time ELSE 0 END), 0) AS playtime "
                + "FROM sessions WHERE login_time BETWEEN ? AND ? "
                + "GROUP BY srv ORDER BY playtime DESC, sessions DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, minMillis);
            ps.setLong(2, fromMillis);
            ps.setLong(3, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                List<ServerRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new ServerRow(rs.getString("srv"), rs.getLong("sessions"),
                            rs.getLong("players"), rs.getLong("playtime")));
                }
                return rows;
            }
        }
    }

    /**
     * Players currently connected to each backend server, derived from still-open sessions
     * (no logout time), ordered by count descending. A null server is bucketed under
     * {@code "(none)"}. This is a live snapshot, so a session left dangling by a crash can
     * linger until {@link #closeAllOpen} runs.
     */
    public List<PresenceRow> currentByServer(Connection connection) throws SQLException {
        String sql = "SELECT COALESCE(server, '(none)') AS srv, COUNT(*) AS online "
                + "FROM sessions WHERE logout_time IS NULL GROUP BY srv ORDER BY online DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<PresenceRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new PresenceRow(rs.getString("srv"), rs.getInt("online")));
            }
            return rows;
        }
    }

    /**
     * Counts of counted (closed, non-ghost) sessions begun within {@code [fromMillis, toMillis]},
     * bucketed by duration into the eight ranges defined by {@code upperBoundsMillis} (each value
     * is the exclusive upper bound of a bucket; the final bucket is open-ended). Returns one count
     * per bucket, in order. Bucketed in SQL so the rows are never materialized.
     */
    public long[] durationHistogram(Connection connection, long fromMillis, long toMillis,
                                    long minMillis, long[] upperBoundsMillis) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < upperBoundsMillis.length; i++) {
            sql.append("SUM(CASE WHEN d >= ? AND d < ? THEN 1 ELSE 0 END), ");
        }
        sql.append("SUM(CASE WHEN d >= ? THEN 1 ELSE 0 END) ");
        sql.append("FROM (SELECT logout_time - login_time AS d FROM sessions "
                + "WHERE login_time BETWEEN ? AND ? AND logout_time IS NOT NULL "
                + "AND logout_time - login_time >= ?)");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            int p = 1;
            long lower = minMillis;
            for (long bound : upperBoundsMillis) {
                ps.setLong(p++, lower);
                ps.setLong(p++, bound);
                lower = bound;
            }
            ps.setLong(p++, lower);     // open-ended final bucket
            ps.setLong(p++, fromMillis);
            ps.setLong(p++, toMillis);
            ps.setLong(p, minMillis);
            try (ResultSet rs = ps.executeQuery()) {
                long[] counts = new long[upperBoundsMillis.length + 1];
                if (rs.next()) {
                    for (int i = 0; i < counts.length; i++) {
                        counts[i] = rs.getLong(i + 1);
                    }
                }
                return counts;
            }
        }
    }

    /**
     * Top players by counted playtime over sessions begun within {@code [fromMillis, toMillis]},
     * limited to {@code limit} rows. The username is taken from the canonical {@code players} row
     * so a renamed account reports its current name. Aggregated and ordered in SQL.
     */
    public List<LeaderRow> leaderboard(Connection connection, long fromMillis, long toMillis,
                                       long minMillis, int limit) throws SQLException {
        String sql = "SELECT p.username AS username, COUNT(*) AS sessions, "
                + "COALESCE(SUM(CASE WHEN s.logout_time IS NOT NULL AND s.logout_time - s.login_time >= ? "
                + "THEN s.logout_time - s.login_time ELSE 0 END), 0) AS playtime "
                + "FROM sessions s JOIN players p ON p.uuid = s.uuid "
                + "WHERE s.login_time BETWEEN ? AND ? "
                + "GROUP BY s.uuid, p.username ORDER BY playtime DESC, sessions DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, minMillis);
            ps.setLong(2, fromMillis);
            ps.setLong(3, toMillis);
            ps.setInt(4, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<LeaderRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new LeaderRow(rs.getString("username"), rs.getLong("sessions"),
                            rs.getLong("playtime")));
                }
                return rows;
            }
        }
    }

    /**
     * Login counts bucketed by day-of-week and hour-of-day (both UTC) over sessions begun within
     * {@code [fromMillis, toMillis]} — the data behind the activity heatmap. Day-of-week follows
     * SQLite's {@code strftime('%w', ...)}: 0 = Sunday through 6 = Saturday. Only non-empty cells
     * are returned; the caller fills the rest with zero.
     */
    public List<HourCell> activityByHour(Connection connection, long fromMillis, long toMillis)
            throws SQLException {
        String sql = "SELECT CAST(strftime('%w', login_time / 1000, 'unixepoch') AS INTEGER) AS dow, "
                + "CAST(strftime('%H', login_time / 1000, 'unixepoch') AS INTEGER) AS hour, "
                + "COUNT(*) AS c FROM sessions WHERE login_time BETWEEN ? AND ? "
                + "GROUP BY dow, hour";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromMillis);
            ps.setLong(2, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                List<HourCell> cells = new ArrayList<>();
                while (rs.next()) {
                    cells.add(new HourCell(rs.getInt("dow"), rs.getInt("hour"), rs.getLong("c")));
                }
                return cells;
            }
        }
    }

    /** Runs a single-aggregate {@code SELECT ... WHERE login_time BETWEEN ? AND ?} query. */
    private long scalarBetween(Connection connection, String sql, long fromMillis, long toMillis)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromMillis);
            ps.setLong(2, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Deletes sessions that began before {@code cutoffMillis}; returns the row count. */
    public int deleteOlderThan(Connection connection, long cutoffMillis) throws SQLException {
        String sql = "DELETE FROM sessions WHERE login_time < ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, cutoffMillis);
            return ps.executeUpdate();
        }
    }

    /** Sessions that have not yet been closed. */
    public List<PlayerSession> openSessions(Connection connection) throws SQLException {
        String sql = "SELECT id, uuid, username, server, login_time, logout_time "
                + "FROM sessions WHERE logout_time IS NULL ORDER BY login_time ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<PlayerSession> results = new ArrayList<>();
            while (rs.next()) {
                results.add(map(rs));
            }
            return results;
        }
    }

    private PlayerSession map(ResultSet rs) throws SQLException {
        long logoutTime = rs.getLong("logout_time");
        Long logout = rs.wasNull() ? null : logoutTime;
        return new PlayerSession(
                rs.getLong("id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("username"),
                rs.getString("server"),
                rs.getLong("login_time"),
                logout
        );
    }
}
