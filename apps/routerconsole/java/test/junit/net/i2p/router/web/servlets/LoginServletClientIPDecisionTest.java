package net.i2p.router.web.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Which address the login blocklist keys on. The load-bearing case is that a
 * directly-reachable console must ignore X-Forwarded-For: honouring it let every
 * guess carry a fresh "client" identity, so the blocklist never engaged.
 */
public class LoginServletClientIPDecisionTest {

    /** Default (no trusted proxy): the spoofable headers are ignored entirely. */
    @Test
    public void testForwardedHeadersIgnoredByDefault() {
        assertEquals("10.0.0.5",
                     LoginServlet.clientIP("1.2.3.4", "9.9.9.9", "10.0.0.5", false));
    }

    /** The bypass itself: a fresh XFF per attempt must not mint a new client. */
    @Test
    public void testSpoofedXFFDoesNotChangeTheKey() {
        String first = LoginServlet.clientIP("203.0.113.1", null, "127.0.0.1", false);
        String next = LoginServlet.clientIP("203.0.113.2", null, "127.0.0.1", false);
        assertEquals("XFF must not create a new identity", first, next);
        assertEquals("127.0.0.1", next);
    }

    /** With an opted-in trusted proxy, XFF is used, and its leftmost entry at that. */
    @Test
    public void testTrustedProxyUsesLeftmostEntry() {
        assertEquals("203.0.113.7",
                     LoginServlet.clientIP("203.0.113.7, 10.0.0.1, 10.0.0.2",
                                           null, "127.0.0.1", true));
    }

    /** X-Real-IP is the fallback when the opted-in proxy sent no XFF. */
    @Test
    public void testTrustedProxyFallsBackToRealIp() {
        assertEquals("203.0.113.9",
                     LoginServlet.clientIP(null, "203.0.113.9", "127.0.0.1", true));
    }

    /** Even opted in, an unusable chain falls back to the connection address. */
    @Test
    public void testTrustedProxyFallsBackWhenHeadersUnusable() {
        assertEquals("127.0.0.1", LoginServlet.clientIP(null, null, "127.0.0.1", true));
        assertEquals("127.0.0.1", LoginServlet.clientIP("", "", "127.0.0.1", true));
        assertEquals("127.0.0.1",
                     LoginServlet.clientIP("unknown", "unknown", "127.0.0.1", true));
        assertEquals("127.0.0.1", LoginServlet.clientIP(" , ", null, "127.0.0.1", true));
    }

    /** A null remote address still yields a stable, non-null key. */
    @Test
    public void testNullRemoteAddrYieldsStableKey() {
        assertEquals("unknown", LoginServlet.clientIP(null, null, null, false));
        assertEquals("unknown", LoginServlet.clientIP(null, null, "", false));
        assertEquals("unknown", LoginServlet.clientIP(null, null, "UNKNOWN", false));
    }

    /** firstForwarded() trims, splits, and treats "unknown" as absent. */
    @Test
    public void testFirstForwardedParsing() {
        assertEquals("1.2.3.4", LoginServlet.firstForwarded("  1.2.3.4  "));
        assertEquals("1.2.3.4", LoginServlet.firstForwarded("1.2.3.4, 5.6.7.8"));
        assertNull(LoginServlet.firstForwarded(null));
        assertNull(LoginServlet.firstForwarded(""));
        assertNull(LoginServlet.firstForwarded("unknown"));
        assertNull(LoginServlet.firstForwarded("UNKNOWN, 1.2.3.4"));
        assertNull(LoginServlet.firstForwarded(","));
    }

    /**
     * Opted in or not, a header value that is not an address cannot become the
     * blocklist key or reach the log line verbatim - the sender chose that text,
     * so it is dropped and the connection address is used instead. The check must
     * not resolve names either: a hostname in the header is rejected, not looked
     * up.
     */
    @Test
    public void testNonAddressHeaderIsRejected() {
        assertEquals("127.0.0.1",
                     LoginServlet.clientIP("<script>alert(1)</script>", null, "127.0.0.1", true));
        assertEquals("127.0.0.1",
                     LoginServlet.clientIP("evil.example.com", null, "127.0.0.1", true));
        assertEquals("127.0.0.1",
                     LoginServlet.clientIP("1.2.3.4; dropped", null, "127.0.0.1", true));
        assertEquals("127.0.0.1",
                     LoginServlet.clientIP("999.999.999.999", null, "127.0.0.1", true));
        assertEquals("127.0.0.1",
                     LoginServlet.clientIP(null, "not an ip", "127.0.0.1", true));
    }

    /**
     * A valid address in the leftmost slot is still used with the validation in
     * place, including an IPv6 one - the guard rejects junk, not the feature.
     */
    @Test
    public void testValidAddressesStillPassTheGuard() {
        assertEquals("203.0.113.7",
                     LoginServlet.clientIP("203.0.113.7, evil.example.com",
                                           null, "127.0.0.1", true));
        assertEquals("2001:db8::1",
                     LoginServlet.clientIP(null, "2001:db8::1", "127.0.0.1", true));
    }

    /**
     * An IPv6 address in the leftmost slot must survive intact - splitting on the
     * first comma, not the first colon, is what keeps it whole.
     */
    @Test
    public void testIPv6LeftmostSurvives() {
        assertEquals("2001:db8::1",
                     LoginServlet.firstForwarded("2001:db8::1, 10.0.0.1"));
        assertEquals("2001:db8::1",
                     LoginServlet.clientIP("2001:db8::1", null, "127.0.0.1", true));
    }
}
