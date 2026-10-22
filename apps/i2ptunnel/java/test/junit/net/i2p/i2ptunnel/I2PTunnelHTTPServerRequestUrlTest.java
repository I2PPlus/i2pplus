package net.i2p.i2ptunnel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The request-line-to-log-string helper. The request line is entirely
 * attacker-controlled, and extractRequestUrl() used to call substring(0, 12) on
 * any host merely containing "b32.i2p" - so a request for "/b32.i2p", 8
 * characters, aborted the eepsite handler with StringIndexOutOfBoundsException.
 *
 * @since 0.9.72+
 */
public class I2PTunnelHTTPServerRequestUrlTest {

    /** A full base32 address: 52 characters of hash followed by the marker. */
    private static final String FULL_B32 =
        "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstu.b32.i2p";

    /** The crash: a short host that still contains the marker. */
    @Test
    public void testShortB32MarkerDoesNotThrow() {
        assertNotNull(I2PTunnelHTTPServer.extractRequestUrl("GET /b32.i2p HTTP/1.1"));
        assertNotNull(I2PTunnelHTTPServer.extractRequestUrl("GET b32.i2p HTTP/1.1"));
        assertNotNull(I2PTunnelHTTPServer.extractRequestUrl("GET /a/b32.i2p HTTP/1.1"));
    }

    /** A full address is shortened to its first 12 characters. */
    @Test
    public void testFullB32AddressIsTruncated() {
        String out = I2PTunnelHTTPServer.extractRequestUrl("GET " + FULL_B32 + " HTTP/1.1");
        assertEquals(FULL_B32.substring(0, 12) + "...b32.i2p", out);
    }

    /** Exactly at the truncation length it still shortens rather than throwing. */
    @Test
    public void testBoundaryAtTruncationLength() {
        String url = "abcde" + "b32.i2p";   // 12 characters
        assertEquals(12, url.length());
        assertEquals(url + "...b32.i2p",
                     I2PTunnelHTTPServer.extractRequestUrl("GET " + url + " HTTP/1.1"));
    }

    /** One short of the truncation length: returned as-is, no exception. */
    @Test
    public void testBelowTruncationLengthReturnedAsIs() {
        String url = "abcd" + "b32.i2p";    // 11 characters
        assertEquals(11, url.length());
        assertEquals(url, I2PTunnelHTTPServer.extractRequestUrl("GET " + url + " HTTP/1.1"));
    }

    /** Missing or empty headers fall back to the placeholder. */
    @Test
    public void testMissingHeaders() {
        assertEquals("Unknown request", I2PTunnelHTTPServer.extractRequestUrl(null));
        assertEquals("Unknown request", I2PTunnelHTTPServer.extractRequestUrl(""));
        assertEquals("Unknown request", I2PTunnelHTTPServer.extractRequestUrl("GET"));
    }

    /** An over-long URL is capped, so the log line stays bounded. */
    @Test
    public void testLongUrlIsCapped() {
        StringBuilder sb = new StringBuilder("/");
        for (int i = 0; i < 300; i++)
            sb.append('a');
        String out = I2PTunnelHTTPServer.extractRequestUrl("GET " + sb + " HTTP/1.1");
        assertTrue("capped result should be at most 99 chars, got " + out.length(),
                   out.length() <= 99);
    }

    /**
     * A battery of hostile request lines: none may throw, none may return null.
     */
    @Test
    public void testHostileInputsNeverThrowOrNull() {
        String[] inputs = {
            "GET / HTTP/1.1",
            "GET // HTTP/1.1",
            "GET /b32.i2p HTTP/1.1",
            "GET b32.i2p HTTP/1.1",
            "GET /.b32.i2p HTTP/1.1",
            "POST xb32.i2px HTTP/1.1",
            "GET http://b32.i2p HTTP/1.1",
            "GET http://aaaaaaaaaaaaaaaaaaaa.b32.i2p/path HTTP/1.1",
            "GET " + FULL_B32 + " HTTP/1.1",
            "GET /" + FULL_B32 + "/a/b/c HTTP/1.1",
            "HEAD ?b32.i2p HTTP/1.1",
            "GET %2eb32.i2p HTTP/1.1",
            "GET /b32.i2p/b32.i2p/b32.i2p HTTP/1.1",
            "\r\n",
            "   ",
            "GET    HTTP/1.1",
            "GET /" + repeat('x', 500) + "b32.i2p HTTP/1.1",
        };
        for (String in : inputs) {
            String out = I2PTunnelHTTPServer.extractRequestUrl(in);
            assertNotNull("null result for [" + in + ']', out);
        }
    }

    /**
     * With a path suffix the host is the joined string, so the shortening keeps
     * the first 12 characters of that and drops the rest. Pinned because it is
     * what the log shows, not because 12 is a natural boundary.
     */
    @Test
    public void testMarkerOnLongHostShortensOnce() {
        String out = I2PTunnelHTTPServer.extractRequestUrl("GET /" + FULL_B32 + "/page.html HTTP/1.1");
        assertTrue(out, out.endsWith("...b32.i2p"));
        assertEquals("12 kept characters plus the 10-character marker", 22, out.length());
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++)
            sb.append(c);
        return sb.toString();
    }
}
