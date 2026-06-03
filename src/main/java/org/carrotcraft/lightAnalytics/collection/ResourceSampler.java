package org.carrotcraft.lightAnalytics.collection;

import com.sun.management.OperatingSystemMXBean;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.carrotcraft.lightAnalytics.model.Snapshot;
import org.carrotcraft.lightAnalytics.storage.Database;
import org.carrotcraft.lightAnalytics.storage.SnapshotRepository;

import java.lang.management.ManagementFactory;
import java.time.Duration;

/**
 * Periodically samples proxy-wide state (online player count and proxy-JVM
 * CPU/RAM) into the {@code snapshots} table. This is the time-series feed for
 * peak-count, population, and resource metrics.
 *
 * <p>The interval is supplied from {@code AnalyticsConfig}.
 */
public final class ResourceSampler {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Database database;
    private final SnapshotRepository snapshots;
    private final Duration sampleInterval;
    private final OperatingSystemMXBean osBean =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private ScheduledTask task;

    public ResourceSampler(Object plugin, ProxyServer proxy, Database database,
                           SnapshotRepository snapshots, Duration sampleInterval) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.database = database;
        this.snapshots = snapshots;
        this.sampleInterval = sampleInterval;
    }

    /** Schedules the recurring sample task. */
    public void start() {
        task = proxy.getScheduler()
                .buildTask(plugin, this::sample)
                .delay(sampleInterval)
                .repeat(sampleInterval)
                .schedule();
    }

    /** Cancels the recurring task, if running. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void sample() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Snapshot snapshot = new Snapshot(
                System.currentTimeMillis(),
                proxy.getPlayerCount(),
                osBean.getProcessCpuLoad(),
                osBean.getCpuLoad(),
                heap.getUsed(),
                heap.getMax()
        );
        database.write(connection -> snapshots.insert(connection, snapshot));
    }
}
