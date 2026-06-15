package org.carrotcraft.lightAnalytics.web;

import org.carrotcraft.lightAnalytics.AnalyticsConfig.WebConfig;
import org.carrotcraft.lightAnalytics.metrics.MetricsService;
import org.carrotcraft.lightAnalytics.model.Snapshot;
import org.carrotcraft.lightAnalytics.storage.Database;
import org.carrotcraft.lightAnalytics.storage.PlayerRepository;
import org.carrotcraft.lightAnalytics.storage.SessionRepository;
import org.carrotcraft.lightAnalytics.storage.SnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end smoke test of the embedded web server over a real loopback socket. */
class WebServerTest {

    private final SnapshotRepository snapshots = new SnapshotRepository();
    private final SessionRepository sessions = new SessionRepository();
    private final PlayerRepository players = new PlayerRepository();

    private Database database;
    private AuthService auth;
    private WebServer server;
    private String base;

    @BeforeEach
    void start(@TempDir Path dir) throws Exception {
        database = new Database(NOPLogger.NOP_LOGGER, dir);
        database.open();
        database.read(connection -> {
            snapshots.insert(connection, new Snapshot(1_000, 5, 0.1, 0.2, 100, 1000));
            return Boolean.TRUE;
        });
        MetricsService metrics = new MetricsService(database, snapshots, sessions, players);

        WebConfig config = new WebConfig(true, "127.0.0.1", 0, "",
                Duration.ofMinutes(120), Duration.ofSeconds(120), 2, false, "", "");
        auth = new AuthService(config.tokenTtl(), config.sessionTtl());
        server = new WebServer(config, metrics, auth, dir, NOPLogger.NOP_LOGGER, 5);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
        if (database != null) database.close();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthIsOpen() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> res = get(client, "/health");
        assertEquals(200, res.statusCode());
        assertEquals("ok", res.body());
    }

    @Test
    void apiRequiresAuth() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> res = get(client, "/api/summary");
        assertEquals(401, res.statusCode());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> res = get(client, "/health");
        assertTrue(res.headers().firstValue("Content-Security-Policy").isPresent());
        assertEquals("nosniff", res.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("DENY", res.headers().firstValue("X-Frame-Options").orElse(""));
    }

    @Test
    void tokenLoginGrantsApiAccess() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        String token = auth.issueToken("tester");
        HttpResponse<String> authRes = get(client, "/auth?token=" + token);
        assertEquals(302, authRes.statusCode());
        assertEquals("/", authRes.headers().firstValue("Location").orElse(""));
        String setCookie = authRes.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.contains("session="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));

        // Carry the session cookie explicitly (Java's cookie store mishandles IP-literal
        // hosts), then the API should let us in.
        String cookie = setCookie.split(";", 2)[0];
        HttpResponse<String> summary = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/summary?window=24h"))
                        .header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, summary.statusCode());
        assertTrue(summary.body().contains("\"currentPopulation\":5"));

        HttpResponse<String> alltime = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/alltime"))
                        .header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, alltime.statusCode());
        assertTrue(alltime.body().contains("\"peakPlayers\":5"));
    }

    @Test
    void badTokenRedirectsToLogin() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpResponse<String> res = get(client, "/auth?token=bogus");
        assertEquals(302, res.statusCode());
        assertEquals("/login", res.headers().firstValue("Location").orElse(""));
    }
}
