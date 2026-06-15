package org.carrotcraft.lightAnalytics.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * Handles {@code GET /auth?token=…}: redeems a single-use login token for a
 * session, sets the session cookie, and {@code 302}-redirects to {@code /} so the
 * token leaves the address bar and browser history. Redemption is rate-limited per
 * client IP. A bad or expired token redirects to the {@code /login} explainer.
 */
public final class AuthHandler implements HttpHandler {

    private final AuthService auth;
    private final RateLimiter limiter;
    private final long sessionMaxAgeSeconds;
    private final boolean secure;
    private final String forwardedSecret;

    public AuthHandler(AuthService auth, RateLimiter limiter, long sessionMaxAgeSeconds,
                       boolean secure, String forwardedSecret) {
        this.auth = auth;
        this.limiter = limiter;
        this.sessionMaxAgeSeconds = sessionMaxAgeSeconds;
        this.secure = secure;
        this.forwardedSecret = forwardedSecret;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals("GET")) {
                WebUtil.sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            if (!limiter.tryAcquire(WebUtil.clientIp(exchange, forwardedSecret))) {
                WebUtil.sendText(exchange, 429, "Too Many Requests");
                return;
            }
            String token = WebUtil.queryParams(exchange).get("token");
            String sessionId = auth.redeem(token);
            if (sessionId == null) {
                WebUtil.sendRedirect(exchange, "/login");
                return;
            }
            exchange.getResponseHeaders().add("Set-Cookie",
                    WebUtil.sessionSetCookie(sessionId, sessionMaxAgeSeconds, secure));
            WebUtil.sendRedirect(exchange, "/");
        } finally {
            exchange.close();
        }
    }
}
