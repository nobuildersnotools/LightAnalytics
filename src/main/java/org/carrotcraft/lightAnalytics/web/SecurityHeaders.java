package org.carrotcraft.lightAnalytics.web;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * Adds defensive HTTP response headers to every request. Set before the handler
 * chain runs (and thus before any handler sends its response), so they apply to
 * all responses.
 *
 * <p>The script source is locked to {@code 'self'} — no inline scripts — which is
 * the meaningful XSS control; inline styles are permitted to keep the markup
 * simple. Framing is forbidden to prevent clickjacking.
 */
public final class SecurityHeaders extends Filter {

    private static final String CSP =
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; connect-src 'self'; base-uri 'none'; "
                    + "form-action 'self'; frame-ancestors 'none'";

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Adds security response headers";
    }
}
