package org.carrotcraft.lightAnalytics.storage;

import org.carrotcraft.lightAnalytics.model.HourlySnapshot;
import org.carrotcraft.lightAnalytics.model.Snapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.carrotcraft.lightAnalytics.storage.SnapshotRepository.HOUR_MILLIS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the retention/compaction SQL against an in-memory SQLite database
 * seeded with the bundled schema. Asserts exact downsampling math so a regression
 * in the rollup aggregate is caught.
 */
class CompactionTest {

    private Connection connection;
    private final SnapshotRepository snapshots = new SnapshotRepository();
    private final SessionRepository sessions = new SessionRepository();

    @BeforeEach
    void open() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        connection = dataSource.getConnection();
        applySchema();
    }

    @AfterEach
    void close() throws SQLException {
        connection.close();
    }

    private void applySchema() throws Exception {
        String schema;
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    @Test
    void downsampleRollsCompleteBucketsAndDeletesRaw() throws SQLException {
        long bucketX = 2 * HOUR_MILLIS;
        long bucketY = 3 * HOUR_MILLIS;
        long recent = 100 * HOUR_MILLIS;

        // Bucket X: three samples -> counts {5,10,3}
        snapshots.insert(connection, new Snapshot(bucketX,          5, 0.10, 0.40, 100, 1000));
        snapshots.insert(connection, new Snapshot(bucketX + 1_000, 10, 0.20, 0.50, 200, 1000));
        snapshots.insert(connection, new Snapshot(bucketX + 2_000,  3, 0.30, 0.60, 300, 1000));
        // Bucket Y: two samples -> counts {7,1}
        snapshots.insert(connection, new Snapshot(bucketY,         7, 0.50, 0.50, 50, 1000));
        snapshots.insert(connection, new Snapshot(bucketY + 500,   1, 0.70, 0.70, 150, 1000));
        // Recent, newer than the cutoff: must survive untouched.
        snapshots.insert(connection, new Snapshot(recent, 99, 0.9, 0.9, 900, 1000));

        long cutoff = 50 * HOUR_MILLIS; // hour-aligned, between the old buckets and the recent row
        int bucketsWritten = snapshots.downsampleOlderThan(connection, cutoff);
        int rawDeleted = snapshots.deleteRawOlderThan(connection, cutoff);

        assertEquals(2, bucketsWritten);
        assertEquals(5, rawDeleted);

        // Only the recent raw snapshot remains.
        List<Snapshot> remainingRaw = snapshots.between(connection, 0, Long.MAX_VALUE);
        assertEquals(1, remainingRaw.size());
        assertEquals(recent, remainingRaw.get(0).timestamp());

        List<HourlySnapshot> hourly = snapshots.hourlyBetween(connection, 0, Long.MAX_VALUE);
        assertEquals(2, hourly.size());

        HourlySnapshot x = hourly.get(0);
        assertEquals(bucketX, x.bucketStart());
        assertEquals(3, x.sampleCount());
        assertEquals(3, x.playerCountMin());
        assertEquals(10, x.playerCountMax());
        assertEquals(6.0, x.playerCountAvg(), 1e-9);     // (5+10+3)/3
        assertEquals(0.20, x.cpuProcessAvg(), 1e-9);     // (0.1+0.2+0.3)/3
        assertEquals(0.30, x.cpuProcessMax(), 1e-9);
        assertEquals(0.50, x.cpuSystemAvg(), 1e-9);      // (0.4+0.5+0.6)/3
        assertEquals(0.60, x.cpuSystemMax(), 1e-9);
        assertEquals(200.0, x.heapUsedAvg(), 1e-9);      // (100+200+300)/3
        assertEquals(300, x.heapUsedMax());
        assertEquals(1000, x.heapMax());

        HourlySnapshot y = hourly.get(1);
        assertEquals(bucketY, y.bucketStart());
        assertEquals(2, y.sampleCount());
        assertEquals(1, y.playerCountMin());
        assertEquals(7, y.playerCountMax());
        assertEquals(4.0, y.playerCountAvg(), 1e-9);
        assertEquals(0.60, y.cpuProcessAvg(), 1e-9);
        assertEquals(0.70, y.cpuProcessMax(), 1e-9);
        assertEquals(100.0, y.heapUsedAvg(), 1e-9);
        assertEquals(150, y.heapUsedMax());
    }

    @Test
    void downsampleIsIdempotentForCompleteBuckets() throws SQLException {
        long bucket = 2 * HOUR_MILLIS;
        snapshots.insert(connection, new Snapshot(bucket,         4, 0.1, 0.2, 100, 1000));
        snapshots.insert(connection, new Snapshot(bucket + 1_000, 6, 0.3, 0.4, 200, 1000));

        long cutoff = 10 * HOUR_MILLIS;
        snapshots.downsampleOlderThan(connection, cutoff);
        // Re-running before the raw rows are deleted must not change the rollup.
        snapshots.downsampleOlderThan(connection, cutoff);

        List<HourlySnapshot> hourly = snapshots.hourlyBetween(connection, 0, Long.MAX_VALUE);
        assertEquals(1, hourly.size());
        assertEquals(2, hourly.get(0).sampleCount());
        assertEquals(5.0, hourly.get(0).playerCountAvg(), 1e-9);
    }

    @Test
    void deleteOlderThanPrunesOnlyOldSessions() throws SQLException {
        sessions.open(connection, UUID.randomUUID(), "old", "lobby", 1_000);
        sessions.open(connection, UUID.randomUUID(), "mid", "lobby", 5_000);
        sessions.open(connection, UUID.randomUUID(), "new", "lobby", 9_000);

        int deleted = sessions.deleteOlderThan(connection, 5_000);

        assertEquals(1, deleted); // only login_time < 5000
        List<?> remaining = sessions.between(connection, 0, Long.MAX_VALUE);
        assertEquals(2, remaining.size());
        assertTrue(sessions.openSessions(connection).size() == 2);
    }
}
