package org.carrotcraft.lightAnalytics.metrics;

import org.carrotcraft.lightAnalytics.model.HourlySnapshot;
import org.carrotcraft.lightAnalytics.model.Snapshot;
import org.carrotcraft.lightAnalytics.storage.Database;
import org.carrotcraft.lightAnalytics.storage.PlayerRepository;
import org.carrotcraft.lightAnalytics.storage.SessionRepository;
import org.carrotcraft.lightAnalytics.storage.SnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates the metrics math against a real {@link Database} (file-backed SQLite
 * under a temp dir) seeded with hand-computed fixtures. Exercises the full
 * read path, including the single-threaded executor that orders writes.
 */
class MetricsServiceTest {

    // Five known players: p1..p3 first seen inside W=[1000,2000], p4 before, p5 after.
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID P3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID P4 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID P5 = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private Database database;
    private MetricsService metrics;
    private final SnapshotRepository snapshots = new SnapshotRepository();
    private final SessionRepository sessions = new SessionRepository();
    private final PlayerRepository players = new PlayerRepository();

    @BeforeEach
    void open(@TempDir Path dir) throws Exception {
        database = new Database(NOPLogger.NOP_LOGGER, dir);
        database.open();
        metrics = new MetricsService(database, snapshots, sessions, players);
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** Blocks until the seeding work has run on the database thread. */
    private void seed(Database.SqlConsumer work) {
        database.read(connection -> {
            work.accept(connection);
            return Boolean.TRUE;
        });
    }

    private void seedPlayersAndSessions() {
        seed(connection -> {
            players.upsertOnLogin(connection, P1, "p1", 1_100);
            players.upsertOnLogin(connection, P2, "p2", 1_500);
            players.upsertOnLogin(connection, P3, "p3", 1_800);
            players.upsertOnLogin(connection, P4, "p4", 500);
            players.upsertOnLogin(connection, P5, "p5", 2_500);

            // p1: first session in W, plus a return after W -> retained
            long s1 = sessions.open(connection, P1, "p1", "lobby", 1_100);
            sessions.close(connection, s1, 1_100 + 60_000);          // 60s, counted
            long s5 = sessions.open(connection, P1, "p1", "lobby", 3_000);
            sessions.close(connection, s5, 3_000 + 1_000);           // exactly 1s, counted

            // p2: only the session in W -> not retained
            long s2 = sessions.open(connection, P2, "p2", "lobby", 1_500);
            sessions.close(connection, s2, 1_500 + 30_000);          // 30s, counted

            // p3: ghost first session, plus a return just after W -> retained
            long s3 = sessions.open(connection, P3, "p3", null, 1_800);
            sessions.close(connection, s3, 1_800 + 14);              // 14ms ghost, ignored
            sessions.open(connection, P3, "p3", "lobby", 2_001);     // open (no logout)

            // p5: session after W
            long s6 = sessions.open(connection, P5, "p5", "lobby", 2_500);
            sessions.close(connection, s6, 2_500 + 5_000);           // 5s, counted
        });
    }

    @Test
    void newPlayerCountCountsFirstSeenInWindow() {
        seedPlayersAndSessions();
        assertEquals(3, metrics.newPlayerCount(1_000, 2_000)); // p1, p2, p3
    }

    @Test
    void retentionUsesCohortFirstSeenInWindowAndReturnAfterIt() {
        seedPlayersAndSessions();
        Retention r = metrics.retention(1_000, 2_000);
        assertEquals(3, r.cohortSize());        // p1, p2, p3
        assertEquals(2, r.retainedCount());     // p1 (3000), p3 (2001); p2 never returns
        assertEquals(2.0 / 3.0, r.retentionRate(), 1e-9);
    }

    @Test
    void playerbaseCountsUniqueNewReturningRegularAndJoins() {
        seedPlayersAndSessions();

        // Wide window covers every seeded session; regular threshold = 2 sessions.
        PlayerbaseStats wide = metrics.playerbase(1_000, 4_000, 2);
        assertEquals(4, wide.uniquePlayers());     // p1, p2, p3, p5 (p4 has no session)
        assertEquals(6, wide.totalJoins());        // p1x2, p2, p3x2, p5
        assertEquals(4, wide.newPlayers());        // all four first seen in this window
        assertEquals(0, wide.returningPlayers());
        assertEquals(2, wide.regularPlayers());    // p1 and p3 each began 2 sessions
        assertEquals(2, wide.regularThreshold());
        assertEquals(1.5, wide.avgJoinsPerPlayer(), 1e-9); // 6 joins / 4 unique

        // Later window: p1 (1100) and p3 (1800) were first seen earlier -> returning.
        PlayerbaseStats later = metrics.playerbase(2_000, 4_000, 2);
        assertEquals(3, later.uniquePlayers());    // p1@3000, p3@2001, p5@2500
        assertEquals(1, later.newPlayers());       // only p5 first seen here
        assertEquals(2, later.returningPlayers()); // p1, p3
        assertEquals(0, later.regularPlayers());   // one session each within the window
    }

    @Test
    void playerbaseIsZeroOnEmptyDatabase() {
        PlayerbaseStats stats = metrics.playerbase(0, 1_000, 5);
        assertEquals(0, stats.uniquePlayers());
        assertEquals(0, stats.totalJoins());
        assertEquals(0, stats.newPlayers());
        assertEquals(0, stats.returningPlayers());
        assertEquals(0, stats.regularPlayers());
        assertEquals(0.0, stats.avgJoinsPerPlayer(), 1e-9);
    }

    @Test
    void sessionStatsExcludeGhostsAndOpenSessions() {
        seedPlayersAndSessions();
        SessionStats stats = metrics.sessionStats(1_000, 4_000);
        // counted: s1(60000) s2(30000) s5(1000) s6(5000); ghost: s3(14); open: p3@2001
        assertEquals(4, stats.countedSessions());
        assertEquals(96_000, stats.totalPlaytimeMillis());
        assertEquals(24_000.0, stats.averageDurationMillis(), 1e-9);
        assertEquals(1, stats.ghostSessionsIgnored());
        assertEquals(1, stats.openSessionsIgnored());
    }

    @Test
    void peakAndResourceTrendsOverRawSnapshots() {
        seed(connection -> {
            snapshots.insert(connection, new Snapshot(1_000, 5, 0.10, 0.20, 100, 1000));
            snapshots.insert(connection, new Snapshot(1_500, 12, 0.30, 0.40, 300, 1000));
            snapshots.insert(connection, new Snapshot(2_000, 8, 0.20, 0.30, 200, 1000));
        });

        PeakPlayers peak = metrics.peakPlayers(1_000, 2_000);
        assertEquals(12, peak.peak());
        assertEquals(1_500, peak.atTimestamp());

        assertEquals(8, metrics.currentPopulation()); // latest snapshot is t=2000

        List<PopulationPoint> series = metrics.populationSeries(1_000, 2_000);
        assertEquals(3, series.size());
        assertEquals(5, series.get(0).playerCount());

        ResourceTrends trends = metrics.resourceTrends(1_000, 2_000);
        assertEquals(3, trends.sampleCount());
        assertEquals(0.20, trends.cpuProcessAvg(), 1e-9);
        assertEquals(0.30, trends.cpuProcessPeak(), 1e-9);
        assertEquals(0.30, trends.cpuSystemAvg(), 1e-9);
        assertEquals(0.40, trends.cpuSystemPeak(), 1e-9);
        assertEquals(200.0, trends.heapUsedAvg(), 1e-9);
        assertEquals(300, trends.heapUsedPeak());
        assertEquals(1000, trends.heapMax());
    }

    @Test
    void summaryMatchesTheIndividualMetricCalls() {
        seedPlayersAndSessions();
        seed(connection -> {
            snapshots.insert(connection, new Snapshot(1_000, 5, 0.10, 0.20, 100, 1000));
            snapshots.insert(connection, new Snapshot(1_500, 12, 0.30, 0.40, 300, 1000));
            snapshots.insert(connection, new Snapshot(2_000, 8, 0.20, 0.30, 200, 1000));
        });

        long from = 1_000;
        long to = 2_000;
        MetricsService.Summary summary = metrics.summary(from, to);

        assertEquals(metrics.currentPopulation(), summary.currentPopulation());
        assertEquals(metrics.peakPlayers(from, to), summary.peak());
        assertEquals(metrics.populationChange(from, to), summary.population());
        assertEquals(metrics.newPlayerCount(from, to), summary.newPlayers());
        assertEquals(metrics.retention(from, to), summary.retention());
        assertEquals(metrics.sessionStats(from, to), summary.sessions());
        assertEquals(metrics.resourceTrends(from, to), summary.resources());
    }

    @Test
    void populationChangeComparesAgainstPreviousWindow() {
        seed(connection -> {
            // previous window [0, 999]
            snapshots.insert(connection, new Snapshot(500, 2, 0.1, 0.1, 100, 1000));
            snapshots.insert(connection, new Snapshot(900, 4, 0.1, 0.1, 100, 1000));
            // current window [1000, 2000]
            snapshots.insert(connection, new Snapshot(1_000, 5, 0.1, 0.1, 100, 1000));
            snapshots.insert(connection, new Snapshot(1_500, 12, 0.1, 0.1, 100, 1000));
            snapshots.insert(connection, new Snapshot(2_000, 8, 0.1, 0.1, 100, 1000));
        });

        PopulationChange change = metrics.populationChange(1_000, 2_000);
        assertEquals((5 + 12 + 8) / 3.0, change.currentAvg(), 1e-9);
        assertEquals(3.0, change.previousAvg(), 1e-9); // (2+4)/2
        assertEquals(change.currentAvg() - 3.0, change.absoluteChange(), 1e-9);
        assertEquals((change.currentAvg() - 3.0) / 3.0, change.percentChange(), 1e-9);
    }

    @Test
    void allTimeStatsCombinePlayersSnapshotsAndHourly() {
        seed(connection -> {
            players.upsertOnLogin(connection, P1, "p1", 1_000);
            players.upsertOnLogin(connection, P1, "p1", 2_000); // returning -> total_sessions = 2
            players.upsertOnLogin(connection, P2, "p2", 5_000);
            snapshots.insert(connection, new Snapshot(10_000, 7, 0.1, 0.1, 100, 1000));
            snapshots.insertHourly(connection, new HourlySnapshot(
                    0, 5, 1, 25, 4.0, 0.1, 0.2, 0.1, 0.2, 100.0, 200, 1000));
        });

        AllTimeStats stats = metrics.allTimeStats();
        assertEquals(7, stats.currentPopulation());  // latest snapshot
        assertEquals(25, stats.peakPlayers());        // hourly max beats raw 7
        assertEquals(0, stats.peakAt());              // reported at the bucket start
        assertEquals(2, stats.uniquePlayers());       // p1, p2
        assertEquals(3, stats.totalSessions());       // p1 = 2, p2 = 1
        assertEquals(1_000, stats.firstEverSeen());
    }

    @Test
    void allTimePeakPrefersRawWhenHigher() {
        seed(connection -> {
            snapshots.insert(connection, new Snapshot(10_000, 30, 0.1, 0.1, 100, 1000));
            snapshots.insertHourly(connection, new HourlySnapshot(
                    0, 5, 1, 25, 4.0, 0.1, 0.2, 0.1, 0.2, 100.0, 200, 1000));
        });

        PeakPlayers peak = metrics.allTimePeak();
        assertEquals(30, peak.peak());
        assertEquals(10_000, peak.atTimestamp());
    }

    @Test
    void allTimeStatsAreZeroOnEmptyDatabase() {
        AllTimeStats stats = metrics.allTimeStats();
        assertEquals(0, stats.currentPopulation());
        assertEquals(0, stats.peakPlayers());
        assertEquals(-1, stats.peakAt());
        assertEquals(0, stats.uniquePlayers());
        assertEquals(0, stats.totalSessions());
        assertEquals(-1, stats.firstEverSeen());
    }

    @Test
    void seriesMergesRawAndHourlyOrderedAndCapsPoints() {
        seed(connection -> {
            snapshots.insertHourly(connection, new HourlySnapshot(
                    0, 4, 1, 10, 5.0, 0.5, 0.9, 0.6, 0.95, 500.0, 900, 1000));
            snapshots.insert(connection, new Snapshot(3_600_000, 8, 0.40, 0.50, 400, 1000));
            snapshots.insert(connection, new Snapshot(3_660_000, 9, 0.45, 0.55, 450, 1000));
        });

        List<SeriesPoint> all = metrics.series(0, 4_000_000, 1000);
        assertEquals(3, all.size());                       // 1 hourly + 2 raw
        assertEquals(0, all.get(0).timestamp());           // oldest first
        assertEquals(5.0, all.get(0).playerCount(), 1e-9); // hourly average
        assertEquals(8.0, all.get(1).playerCount(), 1e-9);

        List<SeriesPoint> capped = metrics.series(0, 4_000_000, 2);
        assertEquals(2, capped.size());                    // downsampled to the cap
    }

    private static final long DAY = 86_400_000L;

    @Test
    void serversReportsCurrentPresenceAndWindowActivity() {
        seed(connection -> {
            long s1 = sessions.open(connection, P1, "p1", "lobby", 1_000);
            sessions.close(connection, s1, 1_000 + 60_000);          // lobby, 60s
            long s2 = sessions.open(connection, P2, "p2", "lobby", 1_100);
            sessions.close(connection, s2, 1_100 + 30_000);          // lobby, 30s
            sessions.open(connection, P1, "p1", "survival", 2_000);  // open -> survival now
            sessions.open(connection, P2, "p2", "lobby", 2_100);     // open -> lobby now
        });

        ServersReport report = metrics.servers(1_000, 1_000_000);

        // Window activity, ordered by playtime: lobby (90s over 3 sessions, 2 players) then survival.
        assertEquals(2, report.window().size());
        ServersReport.ServerActivity lobby = report.window().get(0);
        assertEquals("lobby", lobby.server());
        assertEquals(3, lobby.sessions());
        assertEquals(2, lobby.uniquePlayers());
        assertEquals(90_000, lobby.playtimeMillis());
        ServersReport.ServerActivity survival = report.window().get(1);
        assertEquals("survival", survival.server());
        assertEquals(1, survival.sessions());
        assertEquals(0, survival.playtimeMillis());                  // still open

        // Current presence is the two still-open sessions, one per server.
        assertEquals(2, report.current().size());
        long online = report.current().stream().mapToLong(ServersReport.ServerPresence::online).sum();
        assertEquals(2, online);
    }

    @Test
    void sessionDistributionBucketsCountedSessionsByDuration() {
        seed(connection -> {
            closeAfter(connection, P1, "lobby", 1_000, 30_000);       // 30s   -> <1m
            closeAfter(connection, P2, "lobby", 1_100, 180_000);      // 3m    -> 1–5m
            closeAfter(connection, P3, "lobby", 1_200, 600_000);      // 10m   -> 5–15m
            closeAfter(connection, P4, "lobby", 1_300, 5_400_000);    // 90m   -> 1–2h
            closeAfter(connection, P5, "lobby", 1_400, 500);          // ghost -> excluded
            sessions.open(connection, P1, "p1", "lobby", 1_500);      // open  -> excluded
        });

        List<DurationBucket> dist = metrics.sessionDistribution(0, 1_000_000);
        assertEquals(8, dist.size());
        assertEquals(1, dist.get(0).count());   // <1m
        assertEquals(1, dist.get(1).count());   // 1–5m
        assertEquals(1, dist.get(2).count());   // 5–15m
        assertEquals(0, dist.get(3).count());   // 15–30m
        assertEquals(0, dist.get(4).count());   // 30–60m
        assertEquals(1, dist.get(5).count());   // 1–2h
        assertEquals(0, dist.get(6).count());   // 2–4h
        assertEquals(0, dist.get(7).count());   // 4h+
    }

    @Test
    void activityHeatmapBucketsLoginsByUtcDayAndHour() {
        seed(connection -> {
            // Epoch 0 is Thursday (strftime %w = 4), hour 0 UTC.
            sessions.open(connection, P1, "p1", "lobby", 0);
            sessions.open(connection, P2, "p2", "lobby", 0);          // same cell -> count 2
            sessions.open(connection, P3, "p3", "lobby", 3_600_000);  // Thu hour 1
            sessions.open(connection, P4, "p4", "lobby", DAY);        // Friday (5) hour 0
        });

        ActivityHeatmap heatmap = metrics.activityHeatmap(0, 10 * DAY);
        assertEquals(2, heatmap.grid()[4][0]);
        assertEquals(1, heatmap.grid()[4][1]);
        assertEquals(1, heatmap.grid()[5][0]);
        assertEquals(2, heatmap.max());
    }

    @Test
    void leaderboardRanksPlayersByPlaytimeAndHonoursLimit() {
        seed(connection -> {
            players.upsertOnLogin(connection, P1, "p1", 1_000);
            players.upsertOnLogin(connection, P2, "p2", 1_000);
            players.upsertOnLogin(connection, P3, "p3", 1_000);
            closeAfter(connection, P1, "lobby", 1_000, 60_000);       // p1: 2 sessions, 120s
            closeAfter(connection, P1, "lobby", 2_000, 60_000);
            closeAfter(connection, P2, "lobby", 1_500, 300_000);      // p2: 1 session, 300s
            closeAfter(connection, P3, "lobby", 1_800, 500);          // p3: ghost only, 0s
        });

        List<LeaderboardEntry> board = metrics.leaderboard(0, 1_000_000, 10);
        assertEquals(3, board.size());
        assertEquals("p2", board.get(0).username());
        assertEquals(300_000, board.get(0).playtimeMillis());
        assertEquals(1, board.get(0).sessions());
        assertEquals("p1", board.get(1).username());
        assertEquals(120_000, board.get(1).playtimeMillis());
        assertEquals(2, board.get(1).sessions());
        assertEquals("p3", board.get(2).username());
        assertEquals(0, board.get(2).playtimeMillis());

        assertEquals(2, metrics.leaderboard(0, 1_000_000, 2).size());
    }

    @Test
    void stickinessCountsTrailingDayWeekAndMonthActives() {
        long now = 100 * DAY;
        seed(connection -> {
            sessions.open(connection, P1, "p1", "lobby", now - 3_600_000);   // 1h ago  -> DAU
            sessions.open(connection, P2, "p2", "lobby", now - 3 * DAY);     // 3d ago  -> WAU
            sessions.open(connection, P3, "p3", "lobby", now - 15 * DAY);    // 15d ago -> MAU
            sessions.open(connection, P4, "p4", "lobby", now - 40 * DAY);    // 40d ago -> none
        });

        Stickiness stick = metrics.stickiness(now);
        assertEquals(1, stick.dau());
        assertEquals(2, stick.wau());
        assertEquals(3, stick.mau());
        assertEquals(1.0 / 3.0, stick.stickiness(), 1e-9);
    }

    @Test
    void retentionCurveComputesDayOffsetsAndBounce() {
        seed(connection -> {
            // PA: returns after 1 day (counts for d1 only).
            players.upsertOnLogin(connection, P1, "p1", 1_000);
            players.upsertOnLogin(connection, P1, "p1", 1_000 + DAY);
            sessions.open(connection, P1, "p1", "lobby", 1_000);
            sessions.open(connection, P1, "p1", "lobby", 1_000 + DAY);

            // PB: returns after 7 days (d1, d7).
            players.upsertOnLogin(connection, P2, "p2", 1_500);
            players.upsertOnLogin(connection, P2, "p2", 1_500 + 7 * DAY);
            sessions.open(connection, P2, "p2", "lobby", 1_500 + 7 * DAY);

            // PC: returns after 30 days (d1, d7, d30).
            players.upsertOnLogin(connection, P3, "p3", 1_800);
            players.upsertOnLogin(connection, P3, "p3", 1_800 + 30 * DAY);
            sessions.open(connection, P3, "p3", "lobby", 1_800 + 30 * DAY);

            // PD: one login, never returns -> bounced.
            players.upsertOnLogin(connection, P4, "p4", 1_200);
            sessions.open(connection, P4, "p4", "lobby", 1_200);
        });

        RetentionCurve curve = metrics.retentionCurve(1_000, 2_000);
        assertEquals(4, curve.cohortSize());            // PA, PB, PC, PD
        assertEquals(3.0 / 4.0, curve.d1(), 1e-9);      // PA, PB, PC
        assertEquals(2.0 / 4.0, curve.d7(), 1e-9);      // PB, PC
        assertEquals(1.0 / 4.0, curve.d30(), 1e-9);     // PC
        assertEquals(1.0 / 4.0, curve.bounceRate(), 1e-9); // PD only
    }

    /** Opens a session and closes it {@code durationMillis} later. */
    private void closeAfter(java.sql.Connection connection, UUID uuid, String server,
                            long loginTime, long durationMillis) throws java.sql.SQLException {
        long id = sessions.open(connection, uuid, uuid.toString(), server, loginTime);
        sessions.close(connection, id, loginTime + durationMillis);
    }

    @Test
    void peakAndTrendsRecombineRawAndHourly() {
        seed(connection -> {
            // Old, downsampled hour bucket at epoch 0.
            snapshots.insertHourly(connection, new HourlySnapshot(
                    0, 10, 1, 20, 5.0, 0.50, 0.90, 0.60, 0.95, 500.0, 900, 1000));
            // Recent full-resolution sample inside the same query window.
            snapshots.insert(connection, new Snapshot(4_000_000, 8, 0.40, 0.50, 400, 1000));
        });

        long from = 0;
        long to = 5_000_000;

        PeakPlayers peak = metrics.peakPlayers(from, to);
        assertEquals(20, peak.peak());          // hourly max beats the raw 8
        assertEquals(0, peak.atTimestamp());    // reported at the bucket start

        ResourceTrends trends = metrics.resourceTrends(from, to);
        assertEquals(11, trends.sampleCount());                       // 10 + 1
        assertEquals((0.40 + 0.50 * 10) / 11, trends.cpuProcessAvg(), 1e-9);
        assertEquals(0.90, trends.cpuProcessPeak(), 1e-9);            // max(0.40, 0.90)
        assertEquals((400 + 500.0 * 10) / 11, trends.heapUsedAvg(), 1e-9);
        assertEquals(900, trends.heapUsedPeak());                     // max(400, 900)
    }
}
