package org.carrotcraft.lightAnalytics;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * User-editable timing configuration, loaded from {@code config.toml} in the
 * plugin's data directory (TOML being the format Velocity and its plugins
 * conventionally use). On first run a commented default file is written;
 * thereafter the file is read at startup and its values feed the sampler and the
 * retention task.
 *
 * <p>Every value must be a positive whole number. A missing, wrong-typed, or
 * non-positive entry falls back to its default and logs a warning, and a file
 * that cannot be read or parsed falls back to all defaults — so a malformed file
 * never blocks startup. Changes take effect on the next proxy start; there is no
 * live reload.
 */
public final class AnalyticsConfig {

    /** Default snapshot sampling cadence. */
    public static final Duration DEFAULT_SAMPLE_INTERVAL = Duration.ofSeconds(30);
    /** Default cadence of the retention/compaction pass. */
    public static final Duration DEFAULT_COMPACTION_INTERVAL = Duration.ofMinutes(60);
    /** Default full-resolution snapshot retention before downsampling. */
    public static final Duration DEFAULT_SNAPSHOT_RETENTION = Duration.ofDays(7);
    /** Default session retention before pruning. */
    public static final Duration DEFAULT_SESSION_RETENTION = Duration.ofDays(90);

    private static final String FILE_NAME = "config.toml";
    private static final String KEY_SAMPLE = "sample-interval-seconds";
    private static final String KEY_COMPACTION = "compaction-interval-minutes";
    private static final String KEY_SNAPSHOT_RETENTION = "snapshot-retention-days";
    private static final String KEY_SESSION_RETENTION = "session-retention-days";

    private static final String DEFAULT_FILE = """
            # LightAnalytics configuration.
            # Edit these values and restart the proxy to apply them (no live reload).
            # Every value must be a positive whole number; invalid entries fall back
            # to the built-in default and log a warning.

            # How often a snapshot (online player count + proxy-JVM CPU/RAM) is taken.
            sample-interval-seconds = 30

            # How often the retention/compaction task runs.
            compaction-interval-minutes = 60

            # Keep full-resolution snapshots for this many days. Older snapshots are
            # downsampled into hourly rollups (preserving peaks and trends) and the
            # raw rows are then deleted.
            snapshot-retention-days = 7

            # Delete sessions that began more than this many days ago.
            session-retention-days = 90
            """;

    private final Duration sampleInterval;
    private final Duration compactionInterval;
    private final Duration snapshotRetention;
    private final Duration sessionRetention;

    private AnalyticsConfig(Duration sampleInterval, Duration compactionInterval,
                            Duration snapshotRetention, Duration sessionRetention) {
        this.sampleInterval = sampleInterval;
        this.compactionInterval = compactionInterval;
        this.snapshotRetention = snapshotRetention;
        this.sessionRetention = sessionRetention;
    }

    /**
     * Loads the config from {@code <dataDirectory>/config.toml}, writing a
     * commented default file first if none exists. Never throws: any I/O or parse
     * problem is logged and the affected value(s) fall back to their defaults.
     */
    public static AnalyticsConfig load(Path dataDirectory, Logger logger) {
        Path file = dataDirectory.resolve(FILE_NAME);
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(file)) {
                Files.writeString(file, DEFAULT_FILE);
                logger.info("Wrote default LightAnalytics config to {}", file);
            }
        } catch (IOException e) {
            logger.warn("Could not write default config at {}; using default configuration", file, e);
            return defaults();
        }

        Map<String, String> values;
        try {
            values = parse(Files.readAllLines(file));
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read {}; using default configuration", file, e);
            return defaults();
        }
        return new AnalyticsConfig(
                Duration.ofSeconds(positiveLong(values, KEY_SAMPLE,
                        DEFAULT_SAMPLE_INTERVAL.toSeconds(), logger)),
                Duration.ofMinutes(positiveLong(values, KEY_COMPACTION,
                        DEFAULT_COMPACTION_INTERVAL.toMinutes(), logger)),
                Duration.ofDays(positiveLong(values, KEY_SNAPSHOT_RETENTION,
                        DEFAULT_SNAPSHOT_RETENTION.toDays(), logger)),
                Duration.ofDays(positiveLong(values, KEY_SESSION_RETENTION,
                        DEFAULT_SESSION_RETENTION.toDays(), logger))
        );
    }

    /**
     * Parses the small flat {@code key = value} subset of TOML this config uses.
     * Blank lines and {@code #} comments (whole-line or trailing) are ignored, and
     * surrounding whitespace is trimmed from both key and value. Nothing here
     * interprets the value — that is left to {@link #positiveLong}, so a malformed
     * value still falls back to its default with a warning rather than throwing.
     */
    private static Map<String, String> parse(Iterable<String> lines) {
        Map<String, String> values = new HashMap<>();
        for (String line : lines) {
            int comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            if (!key.isEmpty()) {
                values.put(key, line.substring(eq + 1).trim());
            }
        }
        return values;
    }

    /** A config of all built-in defaults, used as the fallback when the file is unusable. */
    public static AnalyticsConfig defaults() {
        return new AnalyticsConfig(DEFAULT_SAMPLE_INTERVAL, DEFAULT_COMPACTION_INTERVAL,
                DEFAULT_SNAPSHOT_RETENTION, DEFAULT_SESSION_RETENTION);
    }

    private static long positiveLong(Map<String, String> values, String key, long fallback, Logger logger) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        long value;
        try {
            value = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            logger.warn("Config value {} is not a whole number (was \"{}\"); using default {}", key, raw, fallback);
            return fallback;
        }
        if (value <= 0) {
            logger.warn("Config value {} must be positive (was {}); using default {}", key, value, fallback);
            return fallback;
        }
        return value;
    }

    public Duration sampleInterval() {
        return sampleInterval;
    }

    public Duration compactionInterval() {
        return compactionInterval;
    }

    public Duration snapshotRetention() {
        return snapshotRetention;
    }

    public Duration sessionRetention() {
        return sessionRetention;
    }
}
