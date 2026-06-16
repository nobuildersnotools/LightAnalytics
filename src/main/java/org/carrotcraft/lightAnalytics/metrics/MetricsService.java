package org.carrotcraft.lightAnalytics.metrics;

import org.carrotcraft.lightAnalytics.model.HourlySnapshot;
import org.carrotcraft.lightAnalytics.model.Snapshot;
import org.carrotcraft.lightAnalytics.storage.Database;
import org.carrotcraft.lightAnalytics.storage.PlayerRepository;
import org.carrotcraft.lightAnalytics.storage.SessionRepository;
import org.carrotcraft.lightAnalytics.storage.SnapshotRepository;
import org.carrotcraft.lightAnalytics.storage.SnapshotRepository.Aggregate;
import org.carrotcraft.lightAnalytics.storage.SnapshotRepository.PeakRow;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 metrics layer. Composes the storage repositories into the figures the
 * project reports (peak/population, churn, playtime, resource trends).
 *
 * <p>Snapshot- and session-derived figures are computed with SQL aggregates rather
 * than by materializing rows: a wide window can span tens of thousands of snapshot
 * or session rows, and pulling those across the single database thread to sum/peak
 * them in Java is pure waste. Only {@link #series} and {@link #populationSeries},
 * which genuinely need per-row data, still fetch rows.
 *
 * <p>Windows are half-open-friendly {@code [from, to]} epoch-millisecond ranges.
 * Snapshot-derived figures recombine full-resolution snapshots with any hourly
 * rollups the window spans, so they remain correct beyond the full-resolution
 * retention horizon. The two resolutions are disjoint in time by construction
 * (compaction deletes raw rows only after rolling them up), so an hourly bucket's
 * sample-count-weighted aggregate adds directly to the raw aggregate.
 */
public final class MetricsService {

    /** Closed sessions shorter than this are treated as ghost connections and excluded from playtime. */
    public static final long MIN_SESSION_MILLIS = 1_000L;

    private final Database database;
    private final SnapshotRepository snapshots;
    private final SessionRepository sessions;
    private final PlayerRepository players;

    public MetricsService(Database database, SnapshotRepository snapshots,
                          SessionRepository sessions, PlayerRepository players) {
        this.database = database;
        this.snapshots = snapshots;
        this.sessions = sessions;
        this.players = players;
    }

    /**
     * Every windowed figure the admin summary and dashboard report, gathered on a
     * single database connection in one {@link Database#read}. This replaces the
     * previous fan-out of seven independent reads — several of which re-ran the same
     * window scan — with one pass: the current-window snapshot aggregates and the
     * new-player cohort count are each computed once and shared across the figures
     * that need them. The dashboard's playerbase strip is fetched separately via
     * {@link #playerbase} (the in-game command does not use it).
     */
    public Summary summary(long from, long to) {
        return database.read(connection -> {
            Aggregate raw = snapshots.aggregateRaw(connection, from, to);
            Aggregate hourly = snapshots.aggregateHourly(connection, from, to);

            PlayerCounts counts = playerCounts(connection, from, to, raw, hourly);
            PeakPlayers peak = new PeakPlayers(counts.peak(), counts.peakAt());
            PopulationChange population = populationChange(connection, from, to, counts.avg());
            ResourceTrends resources = resourceTrends(raw, hourly);

            long cohort = players.firstSeenCount(connection, from, to);
            long retained = players.retainedCount(connection, from, to, to);
            Retention retention = new Retention((int) cohort, (int) retained,
                    cohort == 0 ? 0.0 : (double) retained / cohort);

            return new Summary(
                    currentPopulation(connection),
                    peak,
                    population,
                    (int) cohort,
                    retention,
                    sessionStats(connection, from, to),
                    resources
            );
        });
    }

    /** Current connected population, taken from the most recent snapshot (0 if none yet). */
    public int currentPopulation() {
        return database.read(this::currentPopulation);
    }

    private int currentPopulation(Connection connection) throws SQLException {
        Snapshot latest = snapshots.latest(connection);
        return latest == null ? 0 : latest.playerCount();
    }

    /** Highest concurrent player count over {@code [from, to]} and when it occurred. */
    public PeakPlayers peakPlayers(long from, long to) {
        return database.read(connection -> {
            PlayerCounts counts = playerCounts(connection, from, to);
            return new PeakPlayers(counts.peak(), counts.peakAt());
        });
    }

    /**
     * Highest concurrent player count ever observed, across both full-resolution
     * snapshots and hourly rollups, and when it occurred. The timestamp is exact
     * when the peak still lives in full resolution, otherwise the start of the
     * winning hour bucket.
     */
    public PeakPlayers allTimePeak() {
        return database.read(this::allTimePeak);
    }

    /** Headline all-time figures for the dashboard, gathered on one connection. */
    public AllTimeStats allTimeStats() {
        return database.read(connection -> {
            Snapshot latest = snapshots.latest(connection);
            int current = latest == null ? 0 : latest.playerCount();
            PeakPlayers peak = allTimePeak(connection);
            return new AllTimeStats(
                    current,
                    peak.peak(),
                    peak.atTimestamp(),
                    players.count(connection),
                    players.sumTotalSessions(connection),
                    players.earliestFirstSeen(connection)
            );
        });
    }

    /**
     * Time-series for the dashboard chart over {@code [from, to]}, merging
     * full-resolution snapshots with the hourly rollups the window spans (the two
     * resolutions are disjoint in time by construction). The merged series is then
     * bucket-averaged down to at most {@code maxPoints} points so payloads stay
     * bounded regardless of zoom level; each emitted point weights its inputs by
     * how many raw samples they represent (1 for a snapshot, its sample count for a
     * rollup). A non-positive {@code maxPoints} disables the cap.
     */
    public List<SeriesPoint> series(long from, long to, int maxPoints) {
        return database.read(connection -> {
            List<Weighted> points = new ArrayList<>();
            for (Snapshot s : snapshots.between(connection, from, to)) {
                points.add(new Weighted(s.timestamp(), s.playerCount(), s.cpuProcessLoad(),
                        s.cpuSystemLoad(), s.heapUsed(), s.heapMax(), 1));
            }
            for (HourlySnapshot h : snapshots.hourlyBetween(connection, from, to)) {
                points.add(new Weighted(h.bucketStart(), h.playerCountAvg(), h.cpuProcessAvg(),
                        h.cpuSystemAvg(), h.heapUsedAvg(), h.heapMax(), h.sampleCount()));
            }
            points.sort(java.util.Comparator.comparingLong(Weighted::timestamp));
            return downsample(points, maxPoints);
        });
    }

    /** Combines the all-time full-resolution and hourly peaks on an open connection. */
    private PeakPlayers allTimePeak(Connection connection) throws SQLException {
        Snapshot rawPeak = snapshots.peakSnapshot(connection);
        HourlySnapshot hourlyPeak = snapshots.peakHourly(connection);
        int peak = 0;
        long peakAt = -1;
        if (rawPeak != null) {
            peak = rawPeak.playerCount();
            peakAt = rawPeak.timestamp();
        }
        if (hourlyPeak != null && hourlyPeak.playerCountMax() > peak) {
            peak = hourlyPeak.playerCountMax();
            peakAt = hourlyPeak.bucketStart();
        }
        return new PeakPlayers(peak, peakAt);
    }

    /**
     * Reduces a timestamp-ordered weighted series to at most {@code maxPoints}
     * points by grouping consecutive entries into equal-sized buckets and emitting
     * a weighted average per bucket (heap max is taken as the bucket max). A
     * non-positive cap, or a series already within the cap, is returned as-is.
     */
    private List<SeriesPoint> downsample(List<Weighted> points, int maxPoints) {
        int n = points.size();
        if (maxPoints <= 0 || n <= maxPoints) {
            List<SeriesPoint> out = new ArrayList<>(n);
            for (Weighted p : points) {
                out.add(new SeriesPoint(p.timestamp, p.players, p.cpuProcess, p.cpuSystem, p.heapUsed, p.heapMax));
            }
            return out;
        }
        int groupSize = (n + maxPoints - 1) / maxPoints;
        List<SeriesPoint> out = new ArrayList<>((n + groupSize - 1) / groupSize);
        for (int start = 0; start < n; start += groupSize) {
            int end = Math.min(start + groupSize, n);
            long timeSum = 0;
            long count = 0;
            long weight = 0;
            double players = 0;
            double cpuProcess = 0;
            double cpuSystem = 0;
            double heapUsed = 0;
            long heapMax = 0;
            boolean any = false;
            for (int i = start; i < end; i++) {
                Weighted p = points.get(i);
                timeSum += p.timestamp;
                count++;
                weight += p.weight;
                players += p.players * p.weight;
                cpuProcess += p.cpuProcess * p.weight;
                cpuSystem += p.cpuSystem * p.weight;
                heapUsed += p.heapUsed * p.weight;
                if (!any || p.heapMax > heapMax) heapMax = p.heapMax;
                any = true;
            }
            long w = weight == 0 ? 1 : weight;
            out.add(new SeriesPoint(
                    timeSum / count,
                    players / w,
                    cpuProcess / w,
                    cpuSystem / w,
                    heapUsed / w,
                    heapMax
            ));
        }
        return out;
    }

    /**
     * Full-resolution population-over-time series for {@code [from, to]}, oldest
     * first. Intended for windows inside the full-resolution retention period;
     * older windows have only the coarse aggregates of {@link #peakPlayers} /
     * {@link #populationChange}.
     */
    public List<PopulationPoint> populationSeries(long from, long to) {
        return database.read(connection -> {
            List<Snapshot> raw = snapshots.between(connection, from, to);
            List<PopulationPoint> series = new ArrayList<>(raw.size());
            for (Snapshot s : raw) {
                series.add(new PopulationPoint(s.timestamp(), s.playerCount()));
            }
            return series;
        });
    }

    /**
     * Average population over {@code [from, to]} versus the immediately preceding
     * window of equal length.
     */
    public PopulationChange populationChange(long from, long to) {
        return database.read(connection -> populationChange(connection, from, to,
                playerCounts(connection, from, to).avg()));
    }

    private PopulationChange populationChange(Connection connection, long from, long to, double currentAvg)
            throws SQLException {
        long length = to - from;
        double previousAvg = playerCounts(connection, from - length, from - 1).avg();
        double absolute = currentAvg - previousAvg;
        double percent = previousAvg == 0.0 ? 0.0 : absolute / previousAvg;
        return new PopulationChange(currentAvg, previousAvg, absolute, percent);
    }

    /** Number of players whose first-ever login fell within {@code [from, to]}. */
    public int newPlayerCount(long from, long to) {
        return database.read(connection -> (int) players.firstSeenCount(connection, from, to));
    }

    /**
     * Playerbase composition over {@code [from, to]}: unique players, the new/returning
     * split, total joins, and how many qualify as "regular" — those with at least
     * {@code regularMinSessions} sessions in the window (clamped to a minimum of 1).
     * Returning is {@code unique - new}, floored at zero (a new player always has a
     * session in the window, so it cannot legitimately go negative).
     */
    public PlayerbaseStats playerbase(long from, long to, int regularMinSessions) {
        int threshold = Math.max(1, regularMinSessions);
        return database.read(connection -> {
            long unique = sessions.uniquePlayersBetween(connection, from, to);
            long joins = sessions.joinsBetween(connection, from, to);
            long regular = sessions.regularPlayersBetween(connection, from, to, threshold);
            long newPlayers = players.firstSeenCount(connection, from, to);
            long returning = Math.max(0, unique - newPlayers);
            double avgJoins = unique == 0 ? 0.0 : (double) joins / unique;
            return new PlayerbaseStats(unique, newPlayers, returning, regular, threshold, joins, avgJoins);
        });
    }

    /**
     * Retention of the new-player cohort first seen in {@code [from, to]}: the
     * fraction that started any further session strictly after {@code to}.
     */
    public Retention retention(long from, long to) {
        return database.read(connection -> {
            long cohort = players.firstSeenCount(connection, from, to);
            long retained = players.retainedCount(connection, from, to, to);
            double rate = cohort == 0 ? 0.0 : (double) retained / cohort;
            return new Retention((int) cohort, (int) retained, rate);
        });
    }

    /** Playtime statistics over sessions that began within {@code [from, to]}. */
    public SessionStats sessionStats(long from, long to) {
        return database.read(connection -> sessionStats(connection, from, to));
    }

    private SessionStats sessionStats(Connection connection, long from, long to) throws SQLException {
        SessionRepository.Playtime pt = sessions.playtimeStats(connection, from, to, MIN_SESSION_MILLIS);
        double avg = pt.countedSessions() == 0
                ? 0.0
                : (double) pt.totalPlaytimeMillis() / pt.countedSessions();
        return new SessionStats(pt.countedSessions(), avg, pt.totalPlaytimeMillis(),
                pt.ghostSessions(), pt.openSessions());
    }

    /** Average/peak proxy-JVM CPU and heap usage over {@code [from, to]}. */
    public ResourceTrends resourceTrends(long from, long to) {
        return database.read(connection -> resourceTrends(
                snapshots.aggregateRaw(connection, from, to),
                snapshots.aggregateHourly(connection, from, to)));
    }

    /**
     * Recombines CPU/heap trends from the raw and hourly aggregates of a window.
     * Sums add directly (the hourly aggregate is already sample-count weighted) and
     * peaks take the larger of the two — but only across resolutions that actually
     * contributed samples, so an absent resolution never pulls a peak toward zero
     * (process CPU load can legitimately be negative when unavailable).
     */
    private ResourceTrends resourceTrends(Aggregate raw, Aggregate hourly) {
        long samples = raw.sampleCount() + hourly.sampleCount();
        if (samples == 0) {
            return new ResourceTrends(0, 0, 0, 0, 0, 0, 0, 0);
        }
        boolean hadRaw = raw.sampleCount() > 0;
        double cpuProcessPeak = hadRaw ? raw.cpuProcessPeak() : 0;
        double cpuSystemPeak = hadRaw ? raw.cpuSystemPeak() : 0;
        long heapUsedPeak = hadRaw ? raw.heapUsedPeak() : 0;
        long heapMax = hadRaw ? raw.heapMax() : 0;
        if (hourly.sampleCount() > 0) {
            if (!hadRaw || hourly.cpuProcessPeak() > cpuProcessPeak) cpuProcessPeak = hourly.cpuProcessPeak();
            if (!hadRaw || hourly.cpuSystemPeak() > cpuSystemPeak) cpuSystemPeak = hourly.cpuSystemPeak();
            if (!hadRaw || hourly.heapUsedPeak() > heapUsedPeak) heapUsedPeak = hourly.heapUsedPeak();
            if (!hadRaw || hourly.heapMax() > heapMax) heapMax = hourly.heapMax();
        }
        double cpuProcessSum = raw.cpuProcessSum() + hourly.cpuProcessSum();
        double cpuSystemSum = raw.cpuSystemSum() + hourly.cpuSystemSum();
        double heapUsedSum = raw.heapUsedSum() + hourly.heapUsedSum();
        return new ResourceTrends(
                cpuProcessSum / samples,
                cpuProcessPeak,
                cpuSystemSum / samples,
                cpuSystemPeak,
                heapUsedSum / samples,
                heapUsedPeak,
                heapMax,
                samples
        );
    }

    /** Fetches and combines a window's player-count aggregates. */
    private PlayerCounts playerCounts(Connection connection, long from, long to) throws SQLException {
        return playerCounts(connection, from, to,
                snapshots.aggregateRaw(connection, from, to),
                snapshots.aggregateHourly(connection, from, to));
    }

    /**
     * Combines player counts over a window from already-fetched raw and hourly
     * aggregates. The average is the sample-count-weighted mean across both
     * resolutions; the peak takes the larger of the two stored maxima (raw winning
     * ties, matching the prior row-by-row behaviour) and its exact timestamp/bucket
     * via a bounded {@code ORDER BY ... LIMIT 1} lookup, run only for the resolution
     * that actually holds the peak.
     */
    private PlayerCounts playerCounts(Connection connection, long from, long to, Aggregate raw, Aggregate hourly)
            throws SQLException {
        long samples = raw.sampleCount() + hourly.sampleCount();
        double avg = samples == 0 ? 0.0 : (raw.playerSum() + hourly.playerSum()) / samples;

        boolean hadRaw = raw.sampleCount() > 0;
        int peak = 0;
        long peakAt = -1;
        if (hadRaw) {
            PeakRow r = snapshots.peakPlayerRaw(connection, from, to);
            if (r != null) {
                peak = r.peak();
                peakAt = r.at();
            }
        }
        if (hourly.sampleCount() > 0 && (!hadRaw || hourly.playerPeak() > peak)) {
            PeakRow h = snapshots.peakHourlyInRange(connection, from, to);
            if (h != null) {
                peak = h.peak();
                peakAt = h.at();
            }
        }
        return new PlayerCounts(peak, peakAt, avg);
    }

    /** Every windowed figure the summary report and dashboard summary endpoint share. */
    public record Summary(
            int currentPopulation,
            PeakPlayers peak,
            PopulationChange population,
            int newPlayers,
            Retention retention,
            SessionStats sessions,
            ResourceTrends resources) {
    }

    /** Internal carrier for the combined player-count aggregation. */
    private record PlayerCounts(int peak, long peakAt, double avg) {
    }

    /** A merged series entry awaiting downsampling; {@code weight} is its raw-sample count. */
    private record Weighted(long timestamp, double players, double cpuProcess, double cpuSystem,
                            double heapUsed, long heapMax, long weight) {
    }
}
