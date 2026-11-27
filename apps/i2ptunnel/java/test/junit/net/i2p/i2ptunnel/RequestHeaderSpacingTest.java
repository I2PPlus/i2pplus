package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * How the proxy reads a request header's value when the client did not write
 * the canonical single space after the colon.
 * <p>
 * RFC 9112 5.3 allows any number of spaces and tabs between the colon and the
 * value, and none at all. The proxy matched its headers on {@code "name: "} and
 * cut their values at fixed offsets past that, so a compact header from a
 * server or a proxy on the path - {@code Connection:close} - was not recognised
 * at all, and a value read at a fixed offset started inside the word. Upstream
 * loosened the match in 6375090f0e; the response side of this tunnel was
 * already handled.
 *
 * @since 0.9.72+
 */
public class RequestHeaderSpacingTest {

    /**
     * The parsing cases. Each one calls a static helper directly, so the
     * instance holds no state and needs no tunnel or router.
     *
     * @since 0.9.72+
     */
    public RequestHeaderSpacingTest() {}

    /**
     * The canonical form is unchanged: one space, and the value keeps the case
     * the client wrote it in, because the referer and the credentials are
     * forwarded as sent.
     */
    @Test
    public void readsTheCanonicalFormUnchanged() {
        assertEquals("http://Example.I2P/Path",
                     I2PTunnelHTTPClient.headerValue("Referer: http://Example.I2P/Path", "referer"));
        assertEquals("Basic dXNlcjpwYXNz",
                     I2PTunnelHTTPClient.headerValue("Proxy-Authorization: Basic dXNlcjpwYXNz", "proxy-authorization"));
    }

    /**
     * A compact header still yields its whole value. At the old fixed offset of
     * 12, a compact User-Agent lost its first character, and the header the
     * address-book decision keys on was read as garbage.
     */
    @Test
    public void readsACompactHeaderWhole() {
        String line = "User-Agent:Mozilla/5.0 (X11; Linux)";
        assertEquals("Mozilla/5.0 (X11; Linux)", I2PTunnelHTTPClient.headerValue(line, "user-agent"));
        assertEquals("ozilla/5.0 (X11; Linux)", line.substring(12));
    }

    /**
     * Extra spacing, including the tab RFC 9112 allows, is trimmed away, and so
     * is trailing whitespace the client left behind.
     */
    @Test
    public void trimsAnyAmountOfSpacing() {
        assertEquals("text/html", I2PTunnelHTTPClient.headerValue("Accept:   text/html  ", "accept"));
        assertEquals("document", I2PTunnelHTTPClient.headerValue("Sec-Fetch-Dest:\tdocument", "sec-fetch-dest"));
        assertEquals("no-cors", I2PTunnelHTTPClient.headerValue("Sec-Fetch-Mode:no-cors", "sec-fetch-mode"));
    }

    /**
     * A header with no value is an empty string, not an exception - a client
     * may send "Via:" on its own.
     */
    @Test
    public void readsAMissingValueAsEmpty() {
        assertEquals("", I2PTunnelHTTPClient.headerValue("Via:", "via"));
        assertEquals("", I2PTunnelHTTPClient.headerValue("Via:   ", "via"));
    }

    /**
     * The NTLM block keys on the value, so a compact or over-spaced NTLM header
     * is still blocked, a Basic header whose base64 happens to contain "ntlm"
     * is not, and Proxy-Authorization is left to its own branch.
     */
    @Test
    public void blocksNtlmOnTheValueNotTheSpacing() {
        assertTrue(I2PTunnelHTTPClient.isNtlmAuthorization("authorization:ntlm typ1type2"));
        assertTrue(I2PTunnelHTTPClient.isNtlmAuthorization("authorization:    ntlm typ1type2"));
        assertFalse(I2PTunnelHTTPClient.isNtlmAuthorization("authorization: basic btlmxlZhbHVl"));
        assertFalse(I2PTunnelHTTPClient.isNtlmAuthorization("proxy-authorization: ntlm typ1type2"));
        assertFalse(I2PTunnelHTTPClient.isNtlmAuthorization("authorization:"));
    }
}
