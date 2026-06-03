package org.carrotcraft.lightAnalytics.storage;

import org.carrotcraft.lightAnalytics.model.HourlySnapshot;
import org.carrotcraft.lightAnalytics.model.Snapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists and retrieves interval-sampled {@link Snapshot}s. The
 * {@link #between} and {@link #latest} accessors are the seam the future metrics
 * layer composes into peak counts, population-over-time, and resource trends.
 *
 * <p>All methods take the {@link Connection} supplied by {@link Database}; they
 * are expected to run on the database executor thread.
 */
public final class SnapshotRepository {

    /** Width of an hourly rollup bucket, in milliseconds. */
    public static final long HOUR_MILLIS = 3_600_000L;

    public void insert(Connection connection, Snapshot snapshot) throws SQLException {
        String sql = "INSERT OR REPLACE INTO snapshots "
                + "(timestamp, player_count, cpu_process, cpu_system, heap_used, heap_max) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, snapshot.timestamp());
            ps.setInt(2, snapshot.playerCount());
            ps.setDouble(3, snapshot.cpuProcessLoad());
            ps.setDouble(4, snapshot.cpuSystemLoad());
            ps.setLong(5, snapshot.heapUsed());
            ps.setLong(6, snapshot.heapMax());
            ps.executeUpdate();
        }
    }

    /** Snapshots with {@code fromMillis <= timestamp <= toMillis}, oldest first. */
    public List<Snapshot> between(Connection connection, long fromMillis, long toMillis) throws SQLException {
        String sql = "SELECT timestamp, player_count, cpu_process, cpu_system, heap_used, heap_max "
                + "FROM snapshots WHERE timestamp BETWEEN ? AND ? ORDER BY timestamp ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromMillis);
            ps.setLong(2, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                List<Snapshot> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(map(rs));
                }
                return results;
            }
        }
    }

    /** The most recent snapshot, or null if none have been recorded. */
    public Snapshot latest(Connection connection) throws SQLException {
        String sql = "SELECT timestamp, player_count, cpu_process, cpu_system, heap_used, heap_max "
                + "FROM snapshots ORDER BY timestamp DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? map(rs) : null;
        }
    }

    /**
     * Rolls every full-resolution snapshot older than {@code cutoffMillis} up into
     * its hour bucket in {@code snapshots_hourly}. The caller must pass an
     * hour-aligned cutoff (a multiple of {@link #HOUR_MILLIS}); that guarantees no
     * bucket is split across the boundary, so the rollup is complete and the
     * {@code INSERT OR REPLACE} is idempotent if re-run before {@link #deleteRawOlderThan}.
     *
     * @return the number of hour buckets written
     */
    public int downsampleOlderThan(Connection connection, long cutoffMillis) throws SQLException {
        String sql = "INSERT OR REPLACE INTO snapshots_hourly "
                + "(bucket_start, sample_count, player_count_min, player_count_max, player_count_avg, "
                + "cpu_process_avg, cpu_process_max, cpu_system_avg, cpu_system_max, "
                + "heap_used_avg, heap_used_max, heap_max) "
                + "SELECT timestamp - (timestamp % ?), COUNT(*), "
                + "MIN(player_count), MAX(player_count), AVG(player_count), "
                + "AVG(cpu_process), MAX(cpu_process), AVG(cpu_system), MAX(cpu_system), "
                + "AVG(heap_used), MAX(heap_used), MAX(heap_max) "
                + "FROM snapshots WHERE timestamp < ? "
                + "GROUP BY timestamp - (timestamp % ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, HOUR_MILLIS);
            ps.setLong(2, cutoffMillis);
            ps.setLong(3, HOUR_MILLIS);
            return ps.executeUpdate();
        }
    }

    /** Deletes full-resolution snapshots older than {@code cutoffMillis}; returns the row count. */
    public int deleteRawOlderThan(Connection connection, long cutoffMillis) throws SQLException {
        String sql = "DELETE FROM snapshots WHERE timestamp < ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, cutoffMillis);
            return ps.executeUpdate();
        }
    }

    /** Inserts (or replaces) a single hourly rollup row. Primarily for tests and migrations. */
    public void insertHourly(Connection connection, HourlySnapshot bucket) throws SQLException {
        String sql = "INSERT OR REPLACE INTO snapshots_hourly "
                + "(bucket_start, sample_count, player_count_min, player_count_max, player_count_avg, "
                + "cpu_process_avg, cpu_process_max, cpu_system_avg, cpu_system_max, "
                + "heap_used_avg, heap_used_max, heap_max) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, bucket.bucketStart());
            ps.setInt(2, bucket.sampleCount());
            ps.setInt(3, bucket.playerCountMin());
            ps.setInt(4, bucket.playerCountMax());
            ps.setDouble(5, bucket.playerCountAvg());
            ps.setDouble(6, bucket.cpuProcessAvg());
            ps.setDouble(7, bucket.cpuProcessMax());
            ps.setDouble(8, bucket.cpuSystemAvg());
            ps.setDouble(9, bucket.cpuSystemMax());
            ps.setDouble(10, bucket.heapUsedAvg());
            ps.setLong(11, bucket.heapUsedMax());
            ps.setLong(12, bucket.heapMax());
            ps.executeUpdate();
        }
    }

    /** Hourly rollups with {@code fromMillis <= bucket_start <= toMillis}, oldest first. */
    public List<HourlySnapshot> hourlyBetween(Connection connection, long fromMillis, long toMillis)
            throws SQLException {
        String sql = "SELECT bucket_start, sample_count, player_count_min, player_count_max, player_count_avg, "
                + "cpu_process_avg, cpu_process_max, cpu_system_avg, cpu_system_max, "
                + "heap_used_avg, heap_used_max, heap_max "
                + "FROM snapshots_hourly WHERE bucket_start BETWEEN ? AND ? ORDER BY bucket_start ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, fromMillis);
            ps.setLong(2, toMillis);
            try (ResultSet rs = ps.executeQuery()) {
                List<HourlySnapshot> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapHourly(rs));
                }
                return results;
            }
        }
    }

    private Snapshot map(ResultSet rs) throws SQLException {
        return new Snapshot(
                rs.getLong("timestamp"),
                rs.getInt("player_count"),
                rs.getDouble("cpu_process"),
                rs.getDouble("cpu_system"),
                rs.getLong("heap_used"),
                rs.getLong("heap_max")
        );
    }

    private HourlySnapshot mapHourly(ResultSet rs) throws SQLException {
        return new HourlySnapshot(
                rs.getLong("bucket_start"),
                rs.getInt("sample_count"),
                rs.getInt("player_count_min"),
                rs.getInt("player_count_max"),
                rs.getDouble("player_count_avg"),
                rs.getDouble("cpu_process_avg"),
                rs.getDouble("cpu_process_max"),
                rs.getDouble("cpu_system_avg"),
                rs.getDouble("cpu_system_max"),
                rs.getDouble("heap_used_avg"),
                rs.getLong("heap_used_max"),
                rs.getLong("heap_max")
        );
    }
}
