package org.carrotcraft.lightAnalytics.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the bundled dashboard assets from the {@code /web} classpath directory.
 * Each asset is read once and cached in memory with a content hash {@code ETag},
 * so repeated requests are cheap and conditional requests short-circuit to
 * {@code 304}. Only {@code GET}/{@code HEAD} are accepted and any path escaping
 * the base directory is rejected.
 */
public final class StaticHandler implements HttpHandler {

    private record Asset(byte[] body, String etag, String contentType) {
    }

    private static final String BASE = "/web/";
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon"
    );

    private final String indexFile;
    private final String fixedResource;
    private final Map<String, Asset> cache = new ConcurrentHashMap<>();

    /** Serves a directory tree, mapping {@code /} to {@code indexFile}. */
    public StaticHandler(String indexFile) {
        this(indexFile, null);
    }

    private StaticHandler(String indexFile, String fixedResource) {
        this.indexFile = indexFile;
        this.fixedResource = fixedResource;
    }

    /** A handler that always serves a single named resource, e.g. the login page. */
    public static StaticHandler fixed(String resourceName) {
        return new StaticHandler(resourceName, resourceName);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                WebUtil.sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            String name = resolveName(exchange);
            if (name == null) {
                WebUtil.sendText(exchange, 404, "Not Found");
                return;
            }
            Asset asset = load(name);
            if (asset == null) {
                WebUtil.sendText(exchange, 404, "Not Found");
                return;
            }
            exchange.getResponseHeaders().set("ETag", asset.etag());
            exchange.getResponseHeaders().set("Cache-Control", "max-age=60");
            exchange.getResponseHeaders().set("Content-Type", asset.contentType());
            String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (asset.etag().equals(inm)) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            if (method.equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            WebUtil.send(exchange, 200, asset.body());
        } finally {
            exchange.close();
        }
    }

    private String resolveName(HttpExchange exchange) {
        if (fixedResource != null) {
            return fixedResource;
        }
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/")) {
            return indexFile;
        }
        String relative = path.startsWith("/") ? path.substring(1) : path;
        // Reject traversal and absolute escapes; only simple in-tree names are served.
        if (relative.isEmpty() || relative.contains("..") || relative.startsWith("/")) {
            return null;
        }
        return relative;
    }

    private Asset load(String name) throws IOException {
        Asset cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        byte[] body;
        try (InputStream in = StaticHandler.class.getResourceAsStream(BASE + name)) {
            if (in == null) {
                return null;
            }
            body = in.readAllBytes();
        }
        Asset asset = new Asset(body, etag(body), contentType(name));
        cache.put(name, asset);
        return asset;
    }

    private static String etag(byte[] body) {
        long hash = 1125899906842597L;
        for (byte b : body) {
            hash = 31 * hash + b;
        }
        return '"' + Long.toHexString(hash) + '"';
    }

    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
