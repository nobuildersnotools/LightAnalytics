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

    /** Default loopback bind address for the web dashboard. */
    public static final String DEFAULT_WEB_BIND = "127.0.0.1";
    /** Default web dashboard port. */
    public static final int DEFAULT_WEB_PORT = 8080;
    /** Default session-cookie lifetime. */
    public static final Duration DEFAULT_WEB_SESSION_TTL = Duration.ofMinutes(120);
    /** Default single-use login-token lifetime. */
    public static final Duration DEFAULT_WEB_TOKEN_TTL = Duration.ofSeconds(120);
    /** Default bounded HTTP worker pool size. */
    public static final int DEFAULT_WEB_THREADS = 4;

    private static final String FILE_NAME = "config.toml";
    private static final String KEY_SAMPLE = "sample-interval-seconds";
    private static final String KEY_COMPACTION = "compaction-interval-minutes";
    private static final String KEY_SNAPSHOT_RETENTION = "snapshot-retention-days";
    private static final String KEY_SESSION_RETENTION = "session-retention-days";
    private static final String KEY_WEB_ENABLED = "web-enabled";
    private static final String KEY_WEB_BIND = "web-bind-address";
    private static final String KEY_WEB_PORT = "web-port";
    private static final String KEY_WEB_PUBLIC_URL = "web-public-url";
    private static final String KEY_WEB_SESSION_TTL = "web-session-ttl-minutes";
    private static final String KEY_WEB_TOKEN_TTL = "web-token-ttl-seconds";
    private static final String KEY_WEB_THREADS = "web-threads";
    private static final String KEY_WEB_TLS_ENABLED = "web-tls-enabled";
    private static final String KEY_WEB_TLS_KEYSTORE = "web-tls-keystore";
    private static final String KEY_WEB_TLS_PASSWORD = "web-tls-password";

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

            # --- Web dashboard ---------------------------------------------------
            # An embedded admin web dashboard. Admins run "/lightanalytics web"
            # in-game to receive a single-use login link; no passwords are stored.

            # Master switch for the web dashboard.
            web-enabled = true

            # Interface to bind. Keep this on loopback (127.0.0.1) and put a reverse
            # proxy (nginx/Caddy) in front for TLS, or enable web-tls-* below. Binding
            # to a public address without TLS serves admin data unencrypted.
            web-bind-address = 127.0.0.1

            # Port to listen on.
            web-port = 8080

            # Base URL used to build the login links sent in-game, e.g.
            # https://stats.example.net. Leave blank to derive http://<bind>:<port>
            # (only reachable if that address is reachable from your browser).
            web-public-url =

            # Session-cookie lifetime, in minutes.
            web-session-ttl-minutes = 120

            # Single-use login-token lifetime, in seconds.
            web-token-ttl-seconds = 120

            # Size of the bounded HTTP worker thread pool.
            web-threads = 4

            # Serve HTTPS directly instead of plain HTTP. Requires a PKCS12 keystore.
            web-tls-enabled = false

            # Path to the PKCS12 keystore (relative paths resolve under the plugin
            # data directory) and its password. Only used when web-tls-enabled = true.
            web-tls-keystore =
            web-tls-password =
            """;

    private final Duration sampleInterval;
    private final Duration compactionInterval;
    private final Duration snapshotRetention;
    private final Duration sessionRetention;
    private final WebConfig web;

    private AnalyticsConfig(Duration sampleInterval, Duration compactionInterval,
                            Duration snapshotRetention, Duration sessionRetention, WebConfig web) {
        this.sampleInterval = sampleInterval;
        this.compactionInterval = compactionInterval;
        this.snapshotRetention = snapshotRetention;
        this.sessionRetention = sessionRetention;
        this.web = web;
    }

    /**
     * Web-dashboard settings. {@code publicUrl}, {@code tlsKeystore}, and
     * {@code tlsPassword} may be empty strings when unset.
     */
    public record WebConfig(
            boolean enabled,
            String bindAddress,
            int port,
            String publicUrl,
            Duration sessionTtl,
            Duration tokenTtl,
            int threads,
            boolean tlsEnabled,
            String tlsKeystore,
            String tlsPassword
    ) {
        /** True when the dashboard is bound to a loopback interface. */
        public boolean isLoopbackBind() {
            return bindAddress.equals("127.0.0.1")
                    || bindAddress.equals("::1")
                    || bindAddress.equalsIgnoreCase("localhost");
        }

        /**
         * Base URL (no trailing slash) for login links: the configured
         * {@code web-public-url} if set, otherwise derived from the bind address,
         * port, and TLS setting.
         */
        public String resolvedBaseUrl() {
            if (!publicUrl.isBlank()) {
                return publicUrl.endsWith("/")
                        ? publicUrl.substring(0, publicUrl.length() - 1)
                        : publicUrl;
            }
            return (tlsEnabled ? "https" : "http") + "://" + bindAddress + ":" + port;
        }
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
                        DEFAULT_SESSION_RETENTION.toDays(), logger)),
                buildWeb(values, logger)
        );
    }

    private static WebConfig buildWeb(Map<String, String> values, Logger logger) {
        return new WebConfig(
                boolValue(values, KEY_WEB_ENABLED, true, logger),
                stringValue(values, KEY_WEB_BIND, DEFAULT_WEB_BIND),
                (int) positiveLong(values, KEY_WEB_PORT, DEFAULT_WEB_PORT, logger),
                stringValue(values, KEY_WEB_PUBLIC_URL, ""),
                Duration.ofMinutes(positiveLong(values, KEY_WEB_SESSION_TTL,
                        DEFAULT_WEB_SESSION_TTL.toMinutes(), logger)),
                Duration.ofSeconds(positiveLong(values, KEY_WEB_TOKEN_TTL,
                        DEFAULT_WEB_TOKEN_TTL.toSeconds(), logger)),
                (int) positiveLong(values, KEY_WEB_THREADS, DEFAULT_WEB_THREADS, logger),
                boolValue(values, KEY_WEB_TLS_ENABLED, false, logger),
                stringValue(values, KEY_WEB_TLS_KEYSTORE, ""),
                stringValue(values, KEY_WEB_TLS_PASSWORD, "")
        );
    }

    /** Built-in default web configuration, used when the file is unusable. */
    private static WebConfig defaultWeb() {
        return new WebConfig(true, DEFAULT_WEB_BIND, DEFAULT_WEB_PORT, "",
                DEFAULT_WEB_SESSION_TTL, DEFAULT_WEB_TOKEN_TTL, DEFAULT_WEB_THREADS,
                false, "", "");
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
                DEFAULT_SNAPSHOT_RETENTION, DEFAULT_SESSION_RETENTION, defaultWeb());
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

    /** A trimmed string value, or {@code fallback} when the key is missing or blank. */
    private static String stringValue(Map<String, String> values, String key, String fallback) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim();
    }

    /** A {@code true}/{@code false} value; anything else warns and falls back. */
    private static boolean boolValue(Map<String, String> values, String key, boolean fallback, Logger logger) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase();
        if (normalized.equals("true")) {
            return true;
        }
        if (normalized.equals("false")) {
            return false;
        }
        logger.warn("Config value {} must be true or false (was \"{}\"); using default {}", key, raw, fallback);
        return fallback;
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

    public WebConfig web() {
        return web;
    }
}
