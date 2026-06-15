package org.carrotcraft.lightAnalytics.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Trust rules for {@link WebUtil#resolveClientIp}, the X-Forwarded-For gate. */
class WebUtilTest {

    private static final String SOCKET = "127.0.0.1";

    @Test
    void ignoresForwardedHeaderWhenNoSecretConfigured() {
        assertEquals(SOCKET, WebUtil.resolveClientIp("", "anything", "9.9.9.9", SOCKET));
        assertEquals(SOCKET, WebUtil.resolveClientIp(null, null, "9.9.9.9", SOCKET));
    }

    @Test
    void trustsForwardedForOnlyWithMatchingSecret() {
        assertEquals("9.9.9.9",
                WebUtil.resolveClientIp("s3cret", "s3cret", "9.9.9.9", SOCKET));
    }

    @Test
    void rejectsForwardedForWhenSecretMissingOrWrong() {
        assertEquals(SOCKET, WebUtil.resolveClientIp("s3cret", null, "9.9.9.9", SOCKET));
        assertEquals(SOCKET, WebUtil.resolveClientIp("s3cret", "nope", "9.9.9.9", SOCKET));
    }

    @Test
    void takesRightmostHopSoClientPrependedEntriesAreIgnored() {
        // A client may prepend bogus hops; the proxy appends the address it observed last.
        assertEquals("9.9.9.9",
                WebUtil.resolveClientIp("s3cret", "s3cret", "1.2.3.4, 9.9.9.9", SOCKET));
    }

    @Test
    void fallsBackToSocketWhenForwardedForBlank() {
        assertEquals(SOCKET, WebUtil.resolveClientIp("s3cret", "s3cret", "  ", SOCKET));
    }
}
