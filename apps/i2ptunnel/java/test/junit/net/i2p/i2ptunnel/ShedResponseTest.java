package net.i2p.i2ptunnel;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for the shed-path HTTP response helpers in
 * {@link I2PTunnelHTTPClientBase}: cookie attempt parsing, meta-refresh
 * eligibility, and the two response bodies. Prevents the empty-response
 * regression where a shed socket was closed with zero bytes.
 *
 * @since 0.9.71+
 */
public class ShedResponseTest {

    @Test
    public void testParseAttemptAbsent() {
        assertEquals(0, I2PTunnelHTTPClientBase.parseShedAttempt(null));
        assertEquals(0, I2PTunnelHTTPClientBase.parseShedAttempt(""));
        assertEquals(0, I2PTunnelHTTPClientBase.parseShedAttempt("other=1"));
    }

    @Test
    public void testParseAttemptValues() {
        assertEquals(1, I2PTunnelHTTPClientBase.parseShedAttempt("i2pshed=1"));
        assertEquals(2, I2PTunnelHTTPClientBase.parseShedAttempt("i2pshed=2"));
        assertEquals(1, I2PTunnelHTTPClientBase.parseShedAttempt("a=b; i2pshed=1; c=d"));
        assertEquals(12, I2PTunnelHTTPClientBase.parseShedAttempt("i2pshed=12"));
    }

    @Test
    public void testParseAttemptMalformed() {
        assertEquals(0, I2PTunnelHTTPClientBase.parseShedAttempt("i2pshed="));
        assertEquals(0, I2PTunnelHTTPClientBase.parseShedAttempt("i2pshed=abc"));
    }

    @Test
    public void testRefreshEligibleHtmlGet() {
        assertTrue(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                "GET", "text/html,application/xhtml+xml,*/*;q=0.8", 0));
        assertTrue(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                "get", "text/html", 1));
    }

    @Test
    public void testRefreshBudgetExhausted() {
        assertFalse(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                "GET", "text/html", I2PTunnelHTTPClientBase.SHED_MAX_REFRESH));
        assertFalse(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                "GET", "text/html", 99));
    }

    @Test
    public void testRefreshNotForNonHtmlOrNonGet() {
        assertFalse(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                "GET", "application/octet-stream", 0));
        assertFalse(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                "GET", "*/*", 0));
        assertFalse(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                "POST", "text/html", 0));
        assertFalse(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                "CONNECT", "text/html", 0));
        assertFalse(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                "GET", null, 0));
        assertFalse(I2PTunnelHTTPClientBase.shouldMetaRefresh(
                null, "text/html", 0));
    }

    @Test
    public void testRefreshResponseBody() {
        String resp = I2PTunnelHTTPClientBase.buildShedRefreshResponse(1);
        assertTrue(resp.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(resp.contains("Set-Cookie: i2pshed=1;"));
        assertTrue(resp.contains("<meta http-equiv=refresh content=10>"));
        assertFalse("meta tag must not use quoted attributes",
                    resp.contains("content=\"10\""));
        assertTrue(resp.endsWith("\n"));
    }

    @Test
    public void test503ResponseBody() {
        String resp = I2PTunnelHTTPClientBase.buildShed503Response();
        assertTrue(resp.startsWith("HTTP/1.1 503 Service Unavailable\r\n"));
        assertTrue(resp.contains("Retry-After: 10\r\n"));
        assertTrue(resp.contains("Set-Cookie: i2pshed=0;"));
        assertTrue(resp.contains("Connection: close\r\n"));
    }

    @Test
    public void testRunnerCeilingResolution() {
        assertEquals(512, I2PTunnelClientBase.resolveRunnerCeiling(true, 512));
        assertEquals(0, I2PTunnelClientBase.resolveRunnerCeiling(false, 256));
    }
}
