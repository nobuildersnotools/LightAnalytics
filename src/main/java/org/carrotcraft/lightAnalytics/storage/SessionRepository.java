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
