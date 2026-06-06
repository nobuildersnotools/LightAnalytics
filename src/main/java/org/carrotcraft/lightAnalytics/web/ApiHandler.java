package org.carrotcraft.lightAnalytics.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.carrotcraft.lightAnalytics.metrics.AllTimeStats;
import org.carrotcraft.lightAnalytics.metrics.MetricsService;
import org.carrotcraft.lightAnalytics.metrics.PeakPlayers;
import org.carrotcraft.lightAnalytics.metrics.PopulationChange;
import org.carrotcraft.lightAnalytics.metrics.ResourceTrends;
import org.carrotcraft.lightAnalytics.metrics.Retention;
import org.carrotcraft.lightAnalytics.metrics.SeriesPoint;
import org.carrotcraft.lightAnalytics.metrics.SessionStats;

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
 *   <li>{@code POST /api/logout} — end the session</li>
 * </ul>
 */
public final class ApiHandler implements HttpHandler {

    private static final long SUMMARY_TTL_MILLIS = 10_000L;
    private static final long ALLTIME_TTL_MILLIS = 60_000L;
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

    public ApiHandler(MetricsService metrics, MetricsCache cache, AuthService auth,
                      Clock clock, boolean secure) {
        this.metrics = metrics;
        this.cache = cache;
        this.auth = auth;
        this.clock = clock;
        this.secure = secure;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String route = path.length() > 4 ? path.substring(4) : "";
            switch (route) {
                case "/summary" -> requireGet(exchange, this::summary);
                case "/alltime" -> requireGet(exchange, this::allTime);
                case "/series" -> requireGet(exchange, this::series);
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
        String label = WINDOWS.containsKey(WebUtil.queryParams(exchange).get("window"))
                ? WebUtil.queryParams(exchange).get("window")
                : "24h";
        Duration window = WINDOWS.get(label);
        String json = cache.get("summary:" + label, SUMMARY_TTL_MILLIS, () -> {
            long to = clock.millis();
            long from = to - window.toMillis();
            PeakPlayers peak = metrics.peakPlayers(from, to);
            PopulationChange pop = metrics.populationChange(from, to);
            Retention ret = metrics.retention(from, to);
            SessionStats sessions = metrics.sessionStats(from, to);
            ResourceTrends res = metrics.resourceTrends(from, to);
            return Json.object()
                    .add("window", label)
                    .add("from", from)
                    .add("to", to)
                    .add("currentPopulation", metrics.currentPopulation())
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
                    .add("newPlayers", metrics.newPlayerCount(from, to))
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
        String json = Json.object()
                .add("from", from)
                .add("to", to)
                .addRaw("points", array.build())
                .build();
        WebUtil.sendJson(exchange, 200, json);
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
