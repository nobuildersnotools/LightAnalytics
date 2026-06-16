package org.carrotcraft.lightAnalytics.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.carrotcraft.lightAnalytics.metrics.ActivityHeatmap;
import org.carrotcraft.lightAnalytics.metrics.AllTimeStats;
import org.carrotcraft.lightAnalytics.metrics.DurationBucket;
import org.carrotcraft.lightAnalytics.metrics.LeaderboardEntry;
import org.carrotcraft.lightAnalytics.metrics.MetricsService;
import org.carrotcraft.lightAnalytics.metrics.PeakPlayers;
import org.carrotcraft.lightAnalytics.metrics.PlayerbaseStats;
import org.carrotcraft.lightAnalytics.metrics.PopulationChange;
import org.carrotcraft.lightAnalytics.metrics.ResourceTrends;
import org.carrotcraft.lightAnalytics.metrics.Retention;
import org.carrotcraft.lightAnalytics.metrics.RetentionCurve;
import org.carrotcraft.lightAnalytics.metrics.SeriesPoint;
import org.carrotcraft.lightAnalytics.metrics.ServersReport;
import org.carrotcraft.lightAnalytics.metrics.SessionStats;
import org.carrotcraft.lightAnalytics.metrics.Stickiness;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * JSON API behind {@code /api}, all gated by the session {@link AuthFilter}. Reads
 * flow through {@link MetricsService}, fronted by a short {@link MetricsCache} so
 * dashboard refreshes don't hammer the single database thread.
 *
 * <ul>
 *   <li>{@code GET /api/summary?window=24h|7d|30d} — windowed summary figures</li>
 *   <li>{@code GET /api/alltime} — all-time headline figures</li>
 *   <li>{@code GET /api/series?from&to&maxPoints} — chart time-series</li>
 *   <li>{@code GET /api/servers?window=} — per-backend presence and activity</li>
 *   <li>{@code GET /api/activity?window=} — login heatmap and session-length distribution</li>
 *   <li>{@code GET /api/players?window=} — stickiness, retention curve, playtime leaderboard</li>
 *   <li>{@code POST /api/logout} — end the session</li>
 * </ul>
 */
public final class ApiHandler implements HttpHandler {

    private static final long SUMMARY_TTL_MILLIS = 10_000L;
    private static final long ALLTIME_TTL_MILLIS = 60_000L;
    private static final long SERIES_TTL_MILLIS = 10_000L;
    /** The Servers/Players/Activity pages change slowly and run several queries; cache them longer. */
    private static final long INSIGHTS_TTL_MILLIS = 30_000L;
    private static final int LEADERBOARD_LIMIT = 10;
    /** Quantum for series cache keys, so near-identical zoom/pan ranges share a cached payload. */
    private static final long SERIES_KEY_QUANTUM_MILLIS = 30_000L;
    private static final int DEFAULT_MAX_POINTS = 1000;
    private static final int MAX_POINTS_CAP = 5000;

    private static final Map<String, Duration> WINDOWS = Map.of(
            "24h", Duration.ofHours(24),
            "7d", Duration.ofDays(7),
            "30d", Duration.ofDays(30)
    );

    private final MetricsService metrics;
    private final MetricsCache cache;
    private final AuthService auth;
    private final Clock clock;
    private final boolean secure;
    private final int regularMinSessions;
    private final RateLimiter limiter;
    private final String forwardedSecret;

    public ApiHandler(MetricsService metrics, MetricsCache cache, AuthService auth,
                      Clock clock, boolean secure, int regularMinSessions,
                      RateLimiter limiter, String forwardedSecret) {
        this.metrics = metrics;
        this.cache = cache;
        this.auth = auth;
        this.clock = clock;
        this.secure = secure;
        this.regularMinSessions = regularMinSessions;
        this.limiter = limiter;
        this.forwardedSecret = forwardedSecret;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!limiter.tryAcquire(WebUtil.clientIp(exchange, forwardedSecret))) {
                WebUtil.sendJson(exchange, 429, error("too many requests"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String route = path.length() > 4 ? path.substring(4) : "";
            switch (route) {
                case "/summary" -> requireGet(exchange, this::summary);
                case "/alltime" -> requireGet(exchange, this::allTime);
                case "/series" -> requireGet(exchange, this::series);
                case "/servers" -> requireGet(exchange, this::servers);
                case "/activity" -> requireGet(exchange, this::activity);
                case "/players" -> requireGet(exchange, this::players);
                case "/logout" -> logout(exchange);
                default -> WebUtil.sendJson(exchange, 404, error("not found"));
            }
        } catch (RuntimeException e) {
            WebUtil.sendJson(exchange, 500, error("metrics unavailable"));
        } finally {
            exchange.close();
        }
    }

    private interface Endpoint {
        void handle(HttpExchange exchange) throws IOException;
    }

    private void requireGet(HttpExchange exchange, Endpoint endpoint) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            WebUtil.sendJson(exchange, 405, error("method not allowed"));
            return;
        }
        endpoint.handle(exchange);
    }

    private void summary(HttpExchange exchange) throws IOException {
        String label = windowLabel(exchange);
        Duration window = WINDOWS.get(label);
        String json = cache.get("summary:" + label, SUMMARY_TTL_MILLIS, () -> {
            long to = clock.millis();
            long from = to - window.toMillis();
            MetricsService.Summary summary = metrics.summary(from, to);
            PeakPlayers peak = summary.peak();
            PopulationChange pop = summary.population();
            Retention ret = summary.retention();
            SessionStats sessions = summary.sessions();
            ResourceTrends res = summary.resources();
            PlayerbaseStats base = metrics.playerbase(from, to, regularMinSessions);
            return Json.object()
                    .add("window", label)
                    .add("from", from)
                    .add("to", to)
                    .add("currentPopulation", summary.currentPopulation())
                    .addRaw("peak", Json.object()
                            .add("peak", peak.peak())
                            .add("at", peak.atTimestamp())
                            .build())
                    .addRaw("population", Json.object()
                            .add("currentAvg", pop.currentAvg())
                            .add("previousAvg", pop.previousAvg())
                            .add("absoluteChange", pop.absoluteChange())
                            .add("percentChange", pop.percentChange())
                            .build())
                    .add("newPlayers", summary.newPlayers())
                    .addRaw("playerbase", Json.object()
                            .add("uniquePlayers", base.uniquePlayers())
                            .add("newPlayers", base.newPlayers())
                            .add("returningPlayers", base.returningPlayers())
                            .add("regularPlayers", base.regularPlayers())
                            .add("regularThreshold", base.regularThreshold())
                            .add("totalJoins", base.totalJoins())
                            .add("avgJoinsPerPlayer", base.avgJoinsPerPlayer())
                            .build())
                    .addRaw("retention", Json.object()
                            .add("cohortSize", ret.cohortSize())
                            .add("retainedCount", ret.retainedCount())
                            .add("retentionRate", ret.retentionRate())
                            .build())
                    .addRaw("sessions", Json.object()
                            .add("countedSessions", sessions.countedSessions())
                            .add("averageDurationMillis", sessions.averageDurationMillis())
                            .add("totalPlaytimeMillis", sessions.totalPlaytimeMillis())
                            .add("ghostSessionsIgnored", sessions.ghostSessionsIgnored())
                            .add("openSessionsIgnored", sessions.openSessionsIgnored())
                            .build())
                    .addRaw("resources", Json.object()
                            .add("cpuProcessAvg", res.cpuProcessAvg())
                            .add("cpuProcessPeak", res.cpuProcessPeak())
                            .add("cpuSystemAvg", res.cpuSystemAvg())
                            .add("cpuSystemPeak", res.cpuSystemPeak())
                            .add("heapUsedAvg", res.heapUsedAvg())
                            .add("heapUsedPeak", res.heapUsedPeak())
                            .add("heapMax", res.heapMax())
                            .add("sampleCount", res.sampleCount())
                            .build())
                    .build();
        });
        WebUtil.sendJson(exchange, 200, json);
    }

    private void allTime(HttpExchange exchange) throws IOException {
        String json = cache.get("alltime", ALLTIME_TTL_MILLIS, () -> {
            AllTimeStats stats = metrics.allTimeStats();
            return Json.object()
                    .add("currentPopulation", stats.currentPopulation())
                    .add("peakPlayers", stats.peakPlayers())
                    .add("peakAt", stats.peakAt())
                    .add("uniquePlayers", stats.uniquePlayers())
                    .add("totalSessions", stats.totalSessions())
                    .add("firstEverSeen", stats.firstEverSeen())
                    .build();
        });
        WebUtil.sendJson(exchange, 200, json);
    }

    private void series(HttpExchange exchange) throws IOException {
        Map<String, String> params = WebUtil.queryParams(exchange);
        long now = clock.millis();
        long to = parseLong(params.get("to"), now);
        long from = parseLong(params.get("from"), to - Duration.ofHours(24).toMillis());
        int maxPoints = (int) Math.min(MAX_POINTS_CAP,
                Math.max(1, parseLong(params.get("maxPoints"), DEFAULT_MAX_POINTS)));
        if (from >= to) {
            WebUtil.sendJson(exchange, 400, error("from must be before to"));
            return;
        }
        // Series is the heaviest read; quantize the range into the cache key so a
        // user's stream of zoom/pan requests collapses onto one DB pass per TTL.
        long keyFrom = from - Math.floorMod(from, SERIES_KEY_QUANTUM_MILLIS);
        long keyTo = to - Math.floorMod(to, SERIES_KEY_QUANTUM_MILLIS);
        String cacheKey = "series:" + keyFrom + ":" + keyTo + ":" + maxPoints;
        String json = cache.get(cacheKey, SERIES_TTL_MILLIS, () -> {
            List<SeriesPoint> points = metrics.series(from, to, maxPoints);
            Json.JsonArray array = Json.array();
            for (SeriesPoint p : points) {
                array.addRaw(Json.object()
                        .add("t", p.timestamp())
                        .add("players", p.playerCount())
                        .add("cpuProcess", p.cpuProcess())
                        .add("cpuSystem", p.cpuSystem())
                        .add("heapUsed", p.heapUsed())
                        .add("heapMax", p.heapMax())
                        .build());
            }
            return Json.object()
                    .add("from", from)
                    .add("to", to)
                    .addRaw("points", array.build())
                    .build();
        });
        WebUtil.sendJson(exchange, 200, json);
    }

    private void servers(HttpExchange exchange) throws IOException {
        String label = windowLabel(exchange);
        Duration window = WINDOWS.get(label);
        String json = cache.get("servers:" + label, INSIGHTS_TTL_MILLIS, () -> {
            long to = clock.millis();
            long from = to - window.toMillis();
            ServersReport report = metrics.servers(from, to);
            Json.JsonArray current = Json.array();
            for (ServersReport.ServerPresence p : report.current()) {
                current.addRaw(Json.object()
                        .add("server", p.server())
                        .add("online", p.online())
                        .build());
            }
            Json.JsonArray windowed = Json.array();
            for (ServersReport.ServerActivity a : report.window()) {
                windowed.addRaw(Json.object()
                        .add("server", a.server())
                        .add("sessions", a.sessions())
                        .add("uniquePlayers", a.uniquePlayers())
                        .add("playtimeMillis", a.playtimeMillis())
                        .build());
            }
            return Json.object()
                    .add("window", label)
                    .add("from", from)
                    .add("to", to)
                    .addRaw("current", current.build())
                    .addRaw("activity", windowed.build())
                    .build();
        });
        WebUtil.sendJson(exchange, 200, json);
    }

    private void activity(HttpExchange exchange) throws IOException {
        String label = windowLabel(exchange);
        Duration window = WINDOWS.get(label);
        String json = cache.get("activity:" + label, INSIGHTS_TTL_MILLIS, () -> {
            long to = clock.millis();
            long from = to - window.toMillis();
            ActivityHeatmap heatmap = metrics.activityHeatmap(from, to);
            Json.JsonArray rows = Json.array();
            for (long[] day : heatmap.grid()) {
                Json.JsonArray hours = Json.array();
                for (long count : day) {
                    hours.addRaw(Long.toString(count));
                }
                rows.addRaw(hours.build());
            }
            Json.JsonArray dist = Json.array();
            for (DurationBucket b : metrics.sessionDistribution(from, to)) {
                dist.addRaw(Json.object()
                        .add("label", b.label())
                        .add("lowerMillis", b.lowerMillis())
                        .add("upperMillis", b.upperMillis())
                        .add("count", b.count())
                        .build());
            }
            return Json.object()
                    .add("window", label)
                    .add("from", from)
                    .add("to", to)
                    .addRaw("heatmap", Json.object()
                            .add("max", heatmap.max())
                            .addRaw("grid", rows.build())
                            .build())
                    .addRaw("distribution", dist.build())
                    .build();
        });
        WebUtil.sendJson(exchange, 200, json);
    }

    private void players(HttpExchange exchange) throws IOException {
        String label = windowLabel(exchange);
        Duration window = WINDOWS.get(label);
        String json = cache.get("players:" + label, INSIGHTS_TTL_MILLIS, () -> {
            long to = clock.millis();
            long from = to - window.toMillis();
            Stickiness stick = metrics.stickiness(to);
            RetentionCurve curve = metrics.retentionCurve(from, to);
            Json.JsonArray board = Json.array();
            for (LeaderboardEntry e : metrics.leaderboard(from, to, LEADERBOARD_LIMIT)) {
                board.addRaw(Json.object()
                        .add("username", e.username())
                        .add("sessions", e.sessions())
                        .add("playtimeMillis", e.playtimeMillis())
                        .build());
            }
            return Json.object()
                    .add("window", label)
                    .add("from", from)
                    .add("to", to)
                    .addRaw("stickiness", Json.object()
                            .add("dau", stick.dau())
                            .add("wau", stick.wau())
                            .add("mau", stick.mau())
                            .add("stickiness", stick.stickiness())
                            .build())
                    .addRaw("retention", Json.object()
                            .add("cohortSize", curve.cohortSize())
                            .add("d1", curve.d1())
                            .add("d7", curve.d7())
                            .add("d30", curve.d30())
                            .add("bounceRate", curve.bounceRate())
                            .build())
                    .addRaw("leaderboard", board.build())
                    .build();
        });
        WebUtil.sendJson(exchange, 200, json);
    }

    /** The validated {@code window} query param ({@code 24h}/{@code 7d}/{@code 30d}), defaulting to {@code 24h}. */
    private static String windowLabel(HttpExchange exchange) {
        String requested = WebUtil.queryParams(exchange).get("window");
        return WINDOWS.containsKey(requested) ? requested : "24h";
    }

    private void logout(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            WebUtil.sendJson(exchange, 405, error("method not allowed"));
            return;
        }
        auth.invalidate(WebUtil.cookie(exchange, WebUtil.SESSION_COOKIE));
        exchange.getResponseHeaders().add("Set-Cookie", WebUtil.clearSessionCookie(secure));
        WebUtil.sendJson(exchange, 200, Json.object().add("ok", true).build());
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String error(String message) {
        return Json.object().add("error", message).build();
    }
}
