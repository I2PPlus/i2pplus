package net.i2p.i2ptunnel;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for the package-private fixupURI method extracted from
 * I2PTunnelHTTPClient.clientConnectionRun for bracket/pipe/brace fixup.
 */
public class I2PTunnelHTTPClientHelperTest {

    @Test
    public void testFixupURINormal() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path");
        assertEquals("http://example.com/path", result.toASCIIString());
    }

    @Test
    public void testFixupURINormalWithQuery() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path?foo=bar");
        assertEquals("http://example.com/path?foo=bar", result.toASCIIString());
    }

    @Test
    public void testFixupURINormalWithPort() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com:8080/path");
        assertEquals("http://example.com:8080/path", result.toASCIIString());
    }

    @Test
    public void testFixupURIUnescapedBracketsInPath() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/[path]/file");
        assertEquals("http://example.com/%5Bpath%5D/file", result.toASCIIString());
    }

    @Test
    public void testFixupURIUnescapedBracketsInQuery() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path?foo=%5Bbar%5D");
        assertEquals("http://example.com/path?foo=%5Bbar%5D", result.toASCIIString());
    }

    @Test
    public void testFixupURIPipeInPath() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path%7Cto/file");
        assertEquals("http://example.com/path%7Cto/file", result.toASCIIString());
    }

    @Test
    public void testFixupURIPipeInQuery() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path?foo=bar%7Cbaz");
        assertEquals("http://example.com/path?foo=bar%7Cbaz", result.toASCIIString());
    }

    @Test
    public void testFixupURIBracesInPath() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/%7Bpath%7D/file");
        assertEquals("http://example.com/%7Bpath%7D/file", result.toASCIIString());
    }

    @Test
    public void testFixupURIBracesInQuery() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path?foo=%7Bbar%7D");
        assertEquals("http://example.com/path?foo=%7Bbar%7D", result.toASCIIString());
    }

    @Test
    public void testFixupURIAllSpecialChars() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/%5Bpath%7Cto/%7Bfile%7D%5D");
        assertEquals("http://example.com/%5Bpath%7Cto/%7Bfile%7D%5D", result.toASCIIString());
    }

    @Test
    public void testFixupURIInvalidStillInvalid() {
        try {
            I2PTunnelHTTPClient.fixupURI("not a valid uri at all");
            fail("Should have thrown URISyntaxException");
        } catch (URISyntaxException e) {
            assertNotNull(e.getMessage());
        }
    }

    @Test
    public void testFixupURIInvalidBracketsFixed() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/[invalid");
        assertEquals("http://example.com/%5Binvalid", result.toASCIIString());
    }

    @Test
    public void testFixupURIHttpsNormal() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("https://example.com/path");
        assertEquals("https://example.com/path", result.toASCIIString());
    }

    @Test
    public void testFixupURIWithUserInfo() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://user:pass@example.com/path");
        assertEquals("http://user:pass@example.com/path", result.toASCIIString());
    }

    @Test
    public void testFixupURIWithFragment() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com/path#fragment");
        assertEquals("http://example.com/path#fragment", result.toASCIIString());
    }

    @Test
    public void testFixupURINoPath() throws Exception {
        URI result = I2PTunnelHTTPClient.fixupURI("http://example.com");
        assertEquals("http://example.com", result.toASCIIString());
    }

    // ---------- getConnectRetryDelayMs ----------

    @Test
    public void testConnectRetryDelay_NonPositiveAttemptIsImmediate() {
        assertEquals(0, I2PTunnelHTTPClient.getConnectRetryDelayMs(0));
        assertEquals(0, I2PTunnelHTTPClient.getConnectRetryDelayMs(-1));
    }

    @Test
    public void testConnectRetryDelay_ExponentialSequence() {
        // 1s -> 2s -> 4s from the base delay
        assertEquals(1000, I2PTunnelHTTPClient.getConnectRetryDelayMs(1));
        assertEquals(2000, I2PTunnelHTTPClient.getConnectRetryDelayMs(2));
        assertEquals(4000, I2PTunnelHTTPClient.getConnectRetryDelayMs(3));
    }

    @Test
    public void testConnectRetryDelay_CappedAt8s() {
        // capped at 8s so later retries never stall the request indefinitely
        assertEquals(8000, I2PTunnelHTTPClient.getConnectRetryDelayMs(4));
        assertEquals(8000, I2PTunnelHTTPClient.getConnectRetryDelayMs(5));
        assertEquals(8000, I2PTunnelHTTPClient.getConnectRetryDelayMs(6));
        assertEquals(8000, I2PTunnelHTTPClient.getConnectRetryDelayMs(100));
    }

    @Test
    public void testConnectRetryDelay_TracksRetryConstant() {
        // sanity: the base delay constant drives the sequence
        long base = I2PTunnelHTTPClient.I2P_CONNECT_RETRY_BASE_DELAY;
        assertEquals(base, I2PTunnelHTTPClient.getConnectRetryDelayMs(1));
        assertEquals(base * 8, I2PTunnelHTTPClient.getConnectRetryDelayMs(100));
    }

    // ---------- parseEmptyRetries ----------

    @Test
    public void testParseEmptyRetries_NullDefaultsZero() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries(null));
    }

    @Test
    public void testParseEmptyRetries_EmptyDefaultsZero() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries(""));
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("  "));
    }

    @Test
    public void testParseEmptyRetries_Normal() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("0"));
        assertEquals(2, I2PTunnelHTTPClient.parseEmptyRetries("2"));
        assertEquals(10, I2PTunnelHTTPClient.parseEmptyRetries("10"));
        assertEquals(2, I2PTunnelHTTPClient.parseEmptyRetries("  2  "));
    }

    @Test
    public void testParseEmptyRetries_NegativeClampedToZero() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("-1"));
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("-5"));
    }

    @Test
    public void testParseEmptyRetries_GarbageDefaultsZero() {
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("abc"));
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("2.5"));
        assertEquals(0, I2PTunnelHTTPClient.parseEmptyRetries("--"));
    }
}
