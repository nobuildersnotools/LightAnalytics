package org.carrotcraft.lightAnalytics.web;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * Guards a context behind a valid session cookie. Unauthenticated requests to API
 * contexts receive a {@code 401} JSON body; requests to page contexts are
 * redirected to the {@code /login} explainer so a browser lands somewhere useful.
 */
public final class AuthFilter extends Filter {

    private final AuthService auth;
    private final boolean apiMode;

    public AuthFilter(AuthService auth, boolean apiMode) {
        this.auth = auth;
        this.apiMode = apiMode;
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        String session = WebUtil.cookie(exchange, WebUtil.SESSION_COOKIE);
        if (auth.validate(session)) {
            chain.doFilter(exchange);
            return;
        }
        if (apiMode) {
            WebUtil.sendJson(exchange, 401, Json.object().add("error", "unauthorized").build());
        } else {
            WebUtil.sendRedirect(exchange, "/login");
        }
    }

    @Override
    public String description() {
        return "Requires a valid LightAnalytics session";
    }
}
