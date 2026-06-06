package org.carrotcraft.lightAnalytics.storage;

import org.carrotcraft.lightAnalytics.model.PlayerRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Maintains the one-row-per-player aggregate in the {@code players} table. This
 * is the basis for unique-player counts, new-player rate, and retention metrics
 * in a later phase, which read via {@link #find}.
 *
 * <p>All methods take the {@link Connection} supplied by {@link Database}; they
 * are expected to run on the database executor thread.
 */
public final class PlayerRepository {

    /**
     * Records a login: inserts the player on first sight (setting {@code first_seen}),
     * or on a returning player bumps {@code last_seen}/{@code username} and increments
     * {@code total_sessions}.
     */
    public void upsertOnLogin(Connection connection, UUID uuid, String username, long time) throws SQLException {
        String sql = "INSERT INTO players (uuid, username, first_seen, last_seen, total_sessions) "
                + "VALUES (?, ?, ?, ?, 1) "
                + "ON CONFLICT(uuid) DO UPDATE SET "
                + "username = excluded.username, "
                + "last_seen = excluded.last_seen, "
                + "total_sessions = total_sessions + 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setLong(3, time);
            ps.setLong(4, time);
            ps.executeUpdate();
        }
    }

    /** Updates only {@code last_seen} (e.g. on disconnect). No-op for unknown players. */
    public void markSeen(Connection connection, UUID uuid, long time) throws SQLException {
        String sql = "UPDATE players SET last_seen = ? WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, time);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Players whose {@code first_seen} falls within {@code [fromMillis, toMillis]},
     * oldest first. This is the raw cohort the metrics layer uses for new-player
     * rate and retention; interpretation stays out of the repository.
     */
    public List<PlayerRecord> firstSeenBetween(Connection connection, long fromMillis, long toMillis)
            throws SQLException {
        String sql = "SELECT uuid, username, first_seen, last_seen, total_sessions "
                + "FROM players WHERE first_seen BETWEEN ? AND ? ORDER BY first_seen ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromMillis);
            ps.setLong(2, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                List<PlayerRecord> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(map(rs));
                }
                return results;
            }
        }
    }

    /** Total number of distinct players ever seen. */
    public long count(Connection connection) throws SQLException {
        String sql = "SELECT COUNT(*) FROM players";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /**
     * Sum of {@code total_sessions} across every player — the all-time login count.
     * This survives session pruning because the per-player counter is cumulative
     * and never decremented, unlike rows in the {@code sessions} table.
     */
    public long sumTotalSessions(Connection connection) throws SQLException {
        String sql = "SELECT COALESCE(SUM(total_sessions), 0) FROM players";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** Earliest {@code first_seen} across all players, or -1 if no players exist. */
    public long earliestFirstSeen(Connection connection) throws SQLException {
        String sql = "SELECT MIN(first_seen) FROM players";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                long value = rs.getLong(1);
                return rs.wasNull() ? -1L : value;
            }
            return -1L;
        }
    }

    /** The aggregate record for a player, or null if never seen. */
    public PlayerRecord find(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT uuid, username, first_seen, last_seen, total_sessions "
                + "FROM players WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    private PlayerRecord map(ResultSet rs) throws SQLException {
        return new PlayerRecord(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("username"),
                rs.getLong("first_seen"),
                rs.getLong("last_seen"),
                rs.getInt("total_sessions")
        );
    }
}
