package org.carrotcraft.lightAnalytics;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.carrotcraft.lightAnalytics.collection.ConnectionListener;
import org.carrotcraft.lightAnalytics.collection.ResourceSampler;
import org.carrotcraft.lightAnalytics.collection.RetentionTask;
import org.carrotcraft.lightAnalytics.metrics.MetricsService;
import org.carrotcraft.lightAnalytics.storage.Database;
import org.carrotcraft.lightAnalytics.storage.PlayerRepository;
import org.carrotcraft.lightAnalytics.storage.SessionRepository;
import org.carrotcraft.lightAnalytics.storage.SnapshotRepository;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Plugin entry point. Owns the lifecycle of the collection layer: it opens the
 * database, registers the connection listener, and starts the resource sampler
 * on proxy init, then tears everything down (finalizing open sessions) on
 * shutdown.
 */
public class LightAnalytics {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private Database database;
    private ResourceSampler sampler;
    private RetentionTask retention;
    private MetricsService metrics;
    private final SessionRepository sessions = new SessionRepository();

    @Inject
    public LightAnalytics(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        AnalyticsConfig config = AnalyticsConfig.load(dataDirectory, logger);

        database = new Database(logger, dataDirectory);
        try {
            database.open();
        } catch (Exception e) {
            logger.error("Failed to initialize LightAnalytics database; collection disabled", e);
            database = null;
            return;
        }

        SnapshotRepository snapshots = new SnapshotRepository();
        PlayerRepository players = new PlayerRepository();

        ConnectionListener listener = new ConnectionListener(database, sessions, players);
        proxy.getEventManager().register(this, listener);

        sampler = new ResourceSampler(this, proxy, database, snapshots, config.sampleInterval());
        sampler.start();

        retention = new RetentionTask(this, proxy, database, logger, snapshots, sessions,
                config.compactionInterval(), config.snapshotRetention(), config.sessionRetention());
        retention.start();

        // The metrics layer reads the collected data; no output surface consumes it
        // yet (commands/dashboards are a later phase), but it is wired here so that
        // phase has a ready collaborator.
        metrics = new MetricsService(database, snapshots, sessions, players);

        logger.info("LightAnalytics enabled");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (sampler != null) {
            sampler.stop();
        }
        if (retention != null) {
            retention.stop();
        }
        if (database != null) {
            long now = System.currentTimeMillis();
            database.write(connection -> sessions.closeAllOpen(connection, now));
            database.close();
        }
        logger.info("LightAnalytics disabled");
    }
}
