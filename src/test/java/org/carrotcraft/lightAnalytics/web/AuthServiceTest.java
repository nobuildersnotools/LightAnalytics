package org.carrotcraft.lightAnalytics.web;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    /** A clock whose millis can be advanced by the test. */
    private static final class TestClock extends Clock {
        private long millis = 1_000_000L;

        void advance(Duration d) {
            millis += d.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private AuthService service(TestClock clock) {
        return new AuthService(Duration.ofSeconds(120), Duration.ofMinutes(120), clock);
    }

    @Test
    void tokenIsSingleUse() {
        TestClock clock = new TestClock();
        AuthService auth = service(clock);
        String token = auth.issueToken("admin");

        assertNotNull(auth.redeem(token));
        assertNull(auth.redeem(token), "a token must not be redeemable twice");
    }

    @Test
    void expiredTokenIsRejected() {
        TestClock clock = new TestClock();
        AuthService auth = service(clock);
        String token = auth.issueToken("admin");

        clock.advance(Duration.ofSeconds(121));
        assertNull(auth.redeem(token));
    }

    @Test
    void sessionValidatesUntilInvalidated() {
        TestClock clock = new TestClock();
        AuthService auth = service(clock);
        String session = auth.redeem(auth.issueToken("admin"));

        assertTrue(auth.validate(session));
        auth.invalidate(session);
        assertFalse(auth.validate(session));
    }

    @Test
    void sessionExpiresAfterTtl() {
        TestClock clock = new TestClock();
        AuthService auth = service(clock);
        String session = auth.redeem(auth.issueToken("admin"));

        clock.advance(Duration.ofMinutes(121));
        assertFalse(auth.validate(session));
    }

    @Test
    void rejectsNullAndUnknownSecrets() {
        AuthService auth = service(new TestClock());
        assertNull(auth.redeem(null));
        assertNull(auth.redeem("not-a-real-token"));
        assertFalse(auth.validate(null));
        assertFalse(auth.validate("not-a-real-session"));
    }
}
