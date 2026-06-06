package org.carrotcraft.lightAnalytics.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passwordless authentication for the web dashboard, tied to Velocity
 * permissions. An admin who can run {@code /lightanalytics web} (gated on
 * {@code lightanalytics.admin}) is handed a short-lived, single-use login
 * <em>token</em>; visiting the token URL exchanges it for a longer-lived
 * <em>session</em>, which the browser then carries as a cookie.
 *
 * <p>Both tokens and session ids are 256 bits of {@link SecureRandom} entropy
 * encoded as URL-safe base64. They are the map keys, so a guess must reproduce the
 * full secret to match — which makes timing-based key recovery infeasible and
 * needs no constant-time compare. State is in-memory only: a proxy restart logs
 * everyone out, which is acceptable for an admin tool.
 *
 * <p>All methods are safe to call from multiple HTTP worker threads.
 */
public final class AuthService {

    private static final int SECRET_BYTES = 32;

    private record Pending(String username, long expiresAt) {
    }

    private record Session(String username, long expiresAt) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Map<String, Pending> tokens = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long tokenTtlMillis;
    private final long sessionTtlMillis;
    private final Clock clock;

    public AuthService(Duration tokenTtl, Duration sessionTtl) {
        this(tokenTtl, sessionTtl, Clock.systemUTC());
    }

    AuthService(Duration tokenTtl, Duration sessionTtl, Clock clock) {
        this.tokenTtlMillis = tokenTtl.toMillis();
        this.sessionTtlMillis = sessionTtl.toMillis();
        this.clock = clock;
    }

    /** Issues a single-use login token for {@code username} and returns it. */
    public String issueToken(String username) {
        sweep();
        String token = randomSecret();
        tokens.put(token, new Pending(username, clock.millis() + tokenTtlMillis));
        return token;
    }

    /**
     * Redeems a login token: consumes it (single use), and on success opens a new
     * session and returns its id. Returns {@code null} for an unknown, already-used,
     * or expired token.
     */
    public String redeem(String token) {
        if (token == null) {
            return null;
        }
        Pending pending = tokens.remove(token);
        if (pending == null || clock.millis() > pending.expiresAt()) {
            return null;
        }
        String sessionId = randomSecret();
        sessions.put(sessionId, new Session(pending.username(), clock.millis() + sessionTtlMillis));
        return sessionId;
    }

    /** True if {@code sessionId} names a live, unexpired session. Expired ones are dropped. */
    public boolean validate(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        Session session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        if (clock.millis() > session.expiresAt()) {
            sessions.remove(sessionId);
            return false;
        }
        return true;
    }

    /** Ends a session (logout). No-op for unknown ids. */
    public void invalidate(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    private String randomSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }

    /** Drops expired tokens and sessions so the maps do not grow without bound. */
    private void sweep() {
        long now = clock.millis();
        for (Iterator<Pending> it = tokens.values().iterator(); it.hasNext(); ) {
            if (now > it.next().expiresAt()) {
                it.remove();
            }
        }
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            if (now > it.next().expiresAt()) {
                it.remove();
            }
        }
    }
}
