package org.carrotcraft.lightAnalytics.web;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.carrotcraft.lightAnalytics.AnalyticsConfig.WebConfig;
import org.carrotcraft.lightAnalytics.metrics.MetricsService;
import org.slf4j.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded admin dashboard HTTP server, built on the JDK's {@code HttpServer}
 * (zero extra dependencies). Started on proxy init and stopped on shutdown,
 * mirroring the sampler and retention tasks. Serves plain HTTP by default; when
 * {@code web-tls-enabled} is set it serves HTTPS from a configured PKCS12
 * keystore. All routes carry the {@link SecurityHeaders} filter; the dashboard and
 * API are gated by {@link AuthFilter}.
 */
public final class WebServer {

    private static final int STOP_GRACE_SECONDS = 2;

    private final WebConfig config;
    private final MetricsService metrics;
    private final AuthService auth;
    private final Path dataDirectory;
    private final Logger logger;
    private final Clock clock;

    private HttpServer server;
    private ExecutorService executor;

    public WebServer(WebConfig config, MetricsService metrics, AuthService auth,
                     Path dataDirectory, Logger logger) {
        this(config, metrics, auth, dataDirectory, logger, Clock.systemUTC());
    }

    WebServer(WebConfig config, MetricsService metrics, AuthService auth,
              Path dataDirectory, Logger logger, Clock clock) {
        this.config = config;
        this.metrics = metrics;
        this.auth = auth;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.clock = clock;
    }

    /** Binds and starts the server. Throws if the socket or TLS keystore cannot be set up. */
    public void start() throws IOException {
        if (!config.isLoopbackBind() && !config.tlsEnabled()) {
            logger.warn("LightAnalytics web dashboard is bound to {} without TLS; admin data will be "
                    + "served unencrypted. Bind to 127.0.0.1 behind a reverse proxy, or set web-tls-* "
                    + "to serve HTTPS directly.", config.bindAddress());
        }

        InetSocketAddress address = new InetSocketAddress(config.bindAddress(), config.port());
        boolean secure = config.tlsEnabled();
        server = secure ? createHttps(address) : HttpServer.create(address, 0);

        executor = Executors.newFixedThreadPool(Math.max(1, config.threads()), namedDaemonFactory());
        server.setExecutor(executor);

        MetricsCache cache = new MetricsCache();
        RateLimiter limiter = new RateLimiter(10, 1);
        long sessionMaxAge = config.sessionTtl().toSeconds();

        SecurityHeaders headers = new SecurityHeaders();

        HttpContext root = server.createContext("/", new StaticHandler("index.html"));
        root.getFilters().add(headers);
        root.getFilters().add(new AuthFilter(auth, false));

        HttpContext login = server.createContext("/login", StaticHandler.fixed("login.html"));
        login.getFilters().add(headers);

        HttpContext health = server.createContext("/health",
                exchange -> WebUtil.sendText(exchange, 200, "ok"));
        health.getFilters().add(headers);

        HttpContext authCtx = server.createContext("/auth",
                new AuthHandler(auth, limiter, sessionMaxAge, secure));
        authCtx.getFilters().add(headers);

        HttpContext api = server.createContext("/api",
                new ApiHandler(metrics, cache, auth, clock, secure));
        api.getFilters().add(headers);
        api.getFilters().add(new AuthFilter(auth, true));

        server.start();
        logger.info("LightAnalytics web dashboard listening at {}", config.resolvedBaseUrl());
    }

    /** The actual bound port (useful when configured with port 0), or -1 if not started. */
    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    /** Stops the server and its worker pool. Safe to call if never started. */
    public void stop() {
        if (server != null) {
            server.stop(STOP_GRACE_SECONDS);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        logger.info("LightAnalytics web dashboard stopped");
    }

    private HttpsServer createHttps(InetSocketAddress address) throws IOException {
        try {
            Path keystorePath = Path.of(config.tlsKeystore());
            if (!keystorePath.isAbsolute()) {
                keystorePath = dataDirectory.resolve(config.tlsKeystore());
            }
            char[] password = config.tlsPassword().toCharArray();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystorePath)) {
                keyStore.load(in, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);

            HttpsServer https = HttpsServer.create(address, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(ssl));
            return https;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to initialise TLS for the web dashboard", e);
        }
    }

    private static java.util.concurrent.ThreadFactory namedDaemonFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "LightAnalytics-Web-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
