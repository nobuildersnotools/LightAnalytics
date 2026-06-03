package org.carrotcraft.lightAnalytics.collection;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.carrotcraft.lightAnalytics.storage.Database;
import org.carrotcraft.lightAnalytics.storage.SessionRepository;
import org.carrotcraft.lightAnalytics.storage.SnapshotRepository;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Bounds long-term storage growth. Full-resolution snapshots accrue every 30s
 * (~2,880 rows/day); this task periodically downsamples those older than
 * {@link #SNAPSHOT_FULL_RETENTION} into hourly rollups (preserving long-range
 * peaks and trends) and then deletes the raw rows. Sessions older than
 * {@link #SESSION_RETENTION} are pruned outright; the {@code players} aggregate
 * is tiny and left untouched.
 *
 * <p>The retention windows and pass cadence are supplied from
 * {@code AnalyticsConfig}. All writes go through {@link Database#write} so they
 * run on the single database thread alongside collection.
 */
public final class RetentionTask {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Database database;
    private final Logger logger;
    private final SnapshotRepository snapshots;
    private final SessionRepository sessions;
    private final Duration compactionInterval;
    private final Duration snapshotRetention;
    private final Duration sessionRetention;

    private ScheduledTask task;

    public RetentionTask(Object plugin, ProxyServer proxy, Database database, Logger logger,
                         SnapshotRepository snapshots, SessionRepository sessions,
                         Duration compactionInterval, Duration snapshotRetention, Duration sessionRetention) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.database = database;
        this.logger = logger;
        this.snapshots = snapshots;
        this.sessions = sessions;
        this.compactionInterval = compactionInterval;
        this.snapshotRetention = snapshotRetention;
        this.sessionRetention = sessionRetention;
    }

    /** Schedules the recurring compaction pass (first run one interval from now). */
    public void start() {
        task = proxy.getScheduler()
                .buildTask(plugin, this::compact)
                .delay(compactionInterval)
                .repeat(compactionInterval)
                .schedule();
    }

    /** Cancels the recurring task, if running. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void compact() {
        long now = System.currentTimeMillis();
        // Align the snapshot cutoff to an hour boundary so no bucket is split:
        // every bucket older than the cutoff is complete and gets rolled up exactly
        // once before its raw rows are deleted.
        long rawCutoff = now - snapshotRetention.toMillis();
        long snapshotCutoff = rawCutoff - rawCutoff % SnapshotRepository.HOUR_MILLIS;
        long sessionCutoff = now - sessionRetention.toMillis();

        database.write(connection -> {
            int buckets = snapshots.downsampleOlderThan(connection, snapshotCutoff);
            int rawDeleted = snapshots.deleteRawOlderThan(connection, snapshotCutoff);
            int sessionsDeleted = sessions.deleteOlderThan(connection, sessionCutoff);
            if (buckets > 0 || rawDeleted > 0 || sessionsDeleted > 0) {
                logger.info("LightAnalytics compaction: {} hourly buckets written, {} raw snapshots pruned, "
                        + "{} old sessions pruned", buckets, rawDeleted, sessionsDeleted);
            }
        });
    }
}
