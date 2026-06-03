package org.carrotcraft.lightAnalytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsConfigTest {

    @Test
    void writesDefaultFileWhenMissingAndReturnsDefaults(@TempDir Path dir) {
        AnalyticsConfig config = AnalyticsConfig.load(dir, NOPLogger.NOP_LOGGER);

        assertTrue(Files.exists(dir.resolve("config.toml")), "default file should be created");
        assertEquals(AnalyticsConfig.DEFAULT_SAMPLE_INTERVAL, config.sampleInterval());
        assertEquals(AnalyticsConfig.DEFAULT_COMPACTION_INTERVAL, config.compactionInterval());
        assertEquals(AnalyticsConfig.DEFAULT_SNAPSHOT_RETENTION, config.snapshotRetention());
        assertEquals(AnalyticsConfig.DEFAULT_SESSION_RETENTION, config.sessionRetention());
    }

    @Test
    void readsOverriddenValues(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.toml"), """
                sample-interval-seconds = 15
                compaction-interval-minutes = 30
                snapshot-retention-days = 14
                session-retention-days = 180
                """);

        AnalyticsConfig config = AnalyticsConfig.load(dir, NOPLogger.NOP_LOGGER);

        assertEquals(Duration.ofSeconds(15), config.sampleInterval());
        assertEquals(Duration.ofMinutes(30), config.compactionInterval());
        assertEquals(Duration.ofDays(14), config.snapshotRetention());
        assertEquals(Duration.ofDays(180), config.sessionRetention());
    }

    @Test
    void invalidOrNonPositiveValuesFallBackPerKey(@TempDir Path dir) throws Exception {
        // Each present but semantically invalid for us: a non-numeric value, zero,
        // and a negative. Each falls back to its own default independently, and the
        // one valid override must still be honored.
        Files.writeString(dir.resolve("config.toml"), """
                sample-interval-seconds = "thirty"
                compaction-interval-minutes = 0
                snapshot-retention-days = -5
                session-retention-days = 45
                """);

        AnalyticsConfig config = AnalyticsConfig.load(dir, NOPLogger.NOP_LOGGER);

        assertEquals(AnalyticsConfig.DEFAULT_SAMPLE_INTERVAL, config.sampleInterval());
        assertEquals(AnalyticsConfig.DEFAULT_COMPACTION_INTERVAL, config.compactionInterval());
        assertEquals(AnalyticsConfig.DEFAULT_SNAPSHOT_RETENTION, config.snapshotRetention());
        assertEquals(Duration.ofDays(45), config.sessionRetention());
    }

    @Test
    void malformedValueFallsBackToAllDefaults(@TempDir Path dir) throws Exception {
        // The sole present key has a non-numeric value (falls back to its default);
        // every other key is absent (also defaulted) — so the result is all defaults.
        Files.writeString(dir.resolve("config.toml"), "sample-interval-seconds = notanumber\n");

        AnalyticsConfig config = AnalyticsConfig.load(dir, NOPLogger.NOP_LOGGER);

        assertEquals(AnalyticsConfig.DEFAULT_SAMPLE_INTERVAL, config.sampleInterval());
        assertEquals(AnalyticsConfig.DEFAULT_COMPACTION_INTERVAL, config.compactionInterval());
        assertEquals(AnalyticsConfig.DEFAULT_SNAPSHOT_RETENTION, config.snapshotRetention());
        assertEquals(AnalyticsConfig.DEFAULT_SESSION_RETENTION, config.sessionRetention());
    }
}
