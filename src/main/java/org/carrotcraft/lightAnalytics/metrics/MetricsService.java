package org.carrotcraft.lightAnalytics.metrics;

import org.carrotcraft.lightAnalytics.model.HourlySnapshot;
import org.carrotcraft.lightAnalytics.model.PlayerRecord;
import org.carrotcraft.lightAnalytics.model.PlayerSession;
import org.carrotcraft.lightAnalytics.model.Snapshot;
import org.carrotcraft.lightAnalytics.storage.Database;
import org.carrotcraft.lightAnalytics.storage.PlayerRepository;
import org.carrotcraft.lightAnalytics.storage.SessionRepository;
import org.carrotcraft.lightAnalytics.storage.SnapshotRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 2 metrics layer. Composes the raw range accessors on the storage
 * repositories into the figures the project reports (peak/population, churn,
 * playtime, resource trends). It deliberately holds no aggregation logic in the
 * repositories: it fetches rows via {@link Database#read} and interprets them
 * here, adding purpose-built aggregate methods to the repos only where a pure-SQL
 * aggregate is clearly leaner (e.g. the hourly rollup).
 *
 * <p>Windows are half-open-friendly {@code [from, to]} epoch-millisecond ranges.
 * Snapshot-derived figures recombine full-resolution snapshots with any hourly
 * rollups the window spans, so they remain correct beyond the full-resolution
 * retention horizon.
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

    /** Current connected population, taken from the most recent snapshot (0 if none yet). */
    public int currentPopulation() {
        return database.read(connection -> {
            Snapshot latest = snapshots.latest(connection);
            return latest == null ? 0 : latest.playerCount();
        });
    }

    /** Highest concurrent player count over {@code [from, to]} and when it occurred. */
    public PeakPlayers peakPlayers(long from, long to) {
        return database.read(connection -> {
            PlayerCounts counts = playerCounts(connection, from, to);
            return new PeakPlayers(counts.peak, counts.peakAt);
        });
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
        long length = to - from;
        long prevTo = from - 1;
        long prevFrom = from - length;
        return database.read(connection -> {
            double currentAvg = playerCounts(connection, from, to).avg;
            double previousAvg = playerCounts(connection, prevFrom, prevTo).avg;
            double absolute = currentAvg - previousAvg;
            double percent = previousAvg == 0.0 ? 0.0 : absolute / previousAvg;
            return new PopulationChange(currentAvg, previousAvg, absolute, percent);
        });
    }

    /** Number of players whose first-ever login fell within {@code [from, to]}. */
    public int newPlayerCount(long from, long to) {
        return database.read(connection -> players.firstSeenBetween(connection, from, to).size());
    }

    /**
     * Retention of the new-player cohort first seen in {@code [from, to]}: the
     * fraction that started any further session strictly after {@code to}.
     */
    public Retention retention(long from, long to) {
        return database.read(connection -> {
            List<PlayerRecord> cohort = players.firstSeenBetween(connection, from, to);
            Set<java.util.UUID> returners = sessions.distinctUuidsWithLoginAfter(connection, to);
            int retained = 0;
            for (PlayerRecord player : cohort) {
                if (returners.contains(player.uuid())) {
                    retained++;
                }
            }
            double rate = cohort.isEmpty() ? 0.0 : (double) retained / cohort.size();
            return new Retention(cohort.size(), retained, rate);
        });
    }

    /** Playtime statistics over sessions that began within {@code [from, to]}. */
    public SessionStats sessionStats(long from, long to) {
        return database.read(connection -> {
            List<PlayerSession> windowSessions = sessions.between(connection, from, to);
            long counted = 0;
            long totalPlaytime = 0;
            long ghosts = 0;
            long open = 0;
            for (PlayerSession session : windowSessions) {
                Long logout = session.logoutTime();
                if (logout == null) {
                    open++;
                    continue;
                }
                long duration = logout - session.loginTime();
                if (duration < MIN_SESSION_MILLIS) {
                    ghosts++;
                    continue;
                }
                counted++;
                totalPlaytime += duration;
            }
            double avg = counted == 0 ? 0.0 : (double) totalPlaytime / counted;
            return new SessionStats(counted, avg, totalPlaytime, ghosts, open);
        });
    }

    /** Average/peak proxy-JVM CPU and heap usage over {@code [from, to]}. */
    public ResourceTrends resourceTrends(long from, long to) {
        return database.read(connection -> {
            List<Snapshot> raw = snapshots.between(connection, from, to);
            List<HourlySnapshot> hourly = snapshots.hourlyBetween(connection, from, to);

            double cpuProcessSum = 0;
            double cpuSystemSum = 0;
            double heapUsedSum = 0;
            long samples = 0;
            double cpuProcessPeak = 0;
            double cpuSystemPeak = 0;
            long heapUsedPeak = 0;
            long heapMax = 0;
            boolean any = false;

            for (Snapshot s : raw) {
                cpuProcessSum += s.cpuProcessLoad();
                cpuSystemSum += s.cpuSystemLoad();
                heapUsedSum += s.heapUsed();
                samples++;
                if (!any || s.cpuProcessLoad() > cpuProcessPeak) cpuProcessPeak = s.cpuProcessLoad();
                if (!any || s.cpuSystemLoad() > cpuSystemPeak) cpuSystemPeak = s.cpuSystemLoad();
                if (!any || s.heapUsed() > heapUsedPeak) heapUsedPeak = s.heapUsed();
                if (!any || s.heapMax() > heapMax) heapMax = s.heapMax();
                any = true;
            }
            for (HourlySnapshot h : hourly) {
                cpuProcessSum += h.cpuProcessAvg() * h.sampleCount();
                cpuSystemSum += h.cpuSystemAvg() * h.sampleCount();
                heapUsedSum += h.heapUsedAvg() * h.sampleCount();
                samples += h.sampleCount();
                if (!any || h.cpuProcessMax() > cpuProcessPeak) cpuProcessPeak = h.cpuProcessMax();
                if (!any || h.cpuSystemMax() > cpuSystemPeak) cpuSystemPeak = h.cpuSystemMax();
                if (!any || h.heapUsedMax() > heapUsedPeak) heapUsedPeak = h.heapUsedMax();
                if (!any || h.heapMax() > heapMax) heapMax = h.heapMax();
                any = true;
            }

            if (samples == 0) {
                return new ResourceTrends(0, 0, 0, 0, 0, 0, 0, 0);
            }
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
        });
    }

    /**
     * Recombines player counts over a window from full-resolution snapshots and
     * any hourly rollups it spans. Raw samples weight 1 each; an hourly bucket
     * contributes its mean weighted by its sample count, and its stored max to the
     * peak. The two resolutions are disjoint in time by construction (compaction
     * deletes raw rows only after rolling them up), so there is no double counting.
     */
    private PlayerCounts playerCounts(Connection connection, long from, long to) throws SQLException {
        double sum = 0;
        long samples = 0;
        int peak = 0;
        long peakAt = -1;
        for (Snapshot s : snapshots.between(connection, from, to)) {
            sum += s.playerCount();
            samples++;
            if (peakAt == -1 || s.playerCount() > peak) {
                peak = s.playerCount();
                peakAt = s.timestamp();
            }
        }
        for (HourlySnapshot h : snapshots.hourlyBetween(connection, from, to)) {
            sum += h.playerCountAvg() * h.sampleCount();
            samples += h.sampleCount();
            if (peakAt == -1 || h.playerCountMax() > peak) {
                peak = h.playerCountMax();
                peakAt = h.bucketStart();
            }
        }
        double avg = samples == 0 ? 0.0 : sum / samples;
        return new PlayerCounts(peak, peakAt, avg);
    }

    /** Internal carrier for the combined player-count aggregation. */
    private record PlayerCounts(int peak, long peakAt, double avg) {
    }
}
