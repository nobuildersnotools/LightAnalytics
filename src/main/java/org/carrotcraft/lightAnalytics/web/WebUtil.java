package org.carrotcraft.lightAnalytics.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small request/response helpers shared by the web handlers. */
final class WebUtil {

    static final String SESSION_COOKIE = "session";

    private WebUtil() {
    }

    /** Parses {@code key=value&...} query strings, URL-decoding values. Missing query yields an empty map. */
    static Map<String, String> queryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(decode(pair), "");
            } else {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Returns the value of the named cookie from the request, or null if absent. */
    static String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).equals(name)) {
                    return trimmed.substring(eq + 1);
                }
            }
        }
        return null;
    }

    /** Builds a hardened {@code Set-Cookie} value for the session cookie. */
    static String sessionSetCookie(String value, long maxAgeSeconds, boolean secure) {
        StringBuilder sb = new StringBuilder();
        sb.append(SESSION_COOKIE).append('=').append(value);
        sb.append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=").append(maxAgeSeconds);
        if (secure) {
            sb.append("; Secure");
        }
        return sb.toString();
    }

    /** A {@code Set-Cookie} that immediately clears the session cookie. */
    static String clearSessionCookie(boolean secure) {
        String cookie = SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
        return secure ? cookie + "; Secure" : cookie;
    }

    /** Best-effort client IP, used as a rate-limit key. */
    static String clientIp(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
    }

    static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, status, json.getBytes(StandardCharsets.UTF_8));
    }

    static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        send(exchange, status, text.getBytes(StandardCharsets.UTF_8));
    }

    static void sendRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } else {
            exchange.close();
        }
    }
}
