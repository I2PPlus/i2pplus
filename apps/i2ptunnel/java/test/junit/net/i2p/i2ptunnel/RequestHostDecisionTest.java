package net.i2p.i2ptunnel;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests how a standard server tunnel reads the client's Host header: the host
 * part is taken out of the header, and the check for the router host's own
 * networks reads that host literally, never resolving it. The client picks the
 * header, so a lookup hands a client-chosen name to the router host's resolver
 * and lets any client bill the router for lookups at wire speed.
 *
 * Both helpers are static and need no tunnel or router context.
 *
 * @since 0.9.72+
 */
public class RequestHostDecisionTest {

    // ----- requestHost -----

    @Test
    public void hostDropsThePort() {
        assertEquals("example.com", I2PTunnelHTTPServer.requestHost("example.com:8080"));
        assertEquals("example.com", I2PTunnelHTTPServer.requestHost("example.com"));
    }

    @Test
    public void hostUnwrapsABracketedIPv6Literal() {
        assertEquals("::1", I2PTunnelHTTPServer.requestHost("[::1]:80"));
        assertEquals("2001:db8::1", I2PTunnelHTTPServer.requestHost("[2001:db8::1]"));
    }

    @Test
    public void hostIgnoresSurroundingSpace() {
        assertEquals("example.com", I2PTunnelHTTPServer.requestHost("  example.com  "));
    }

    // ----- isLocalRequestHost -----

    @Test
    public void loopbackIsLocal() {
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("127.0.0.1"));
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("127.2.3.4"));
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("::1"));
    }

    @Test
    public void localhostByNameIsLocal() {
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("localhost"));
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("LOCALHOST"));
        // a name that merely contains it is not the loopback name
        assertFalse(I2PTunnelHTTPServer.isLocalRequestHost("localhost.example.com"));
    }

    @Test
    public void privateAndReservedRangesAreLocal() {
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("10.0.0.5"));
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("172.16.0.1"));
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("192.168.1.1"));
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("169.254.1.1"));
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("fe80::1"));
        // the wildcard address is not a destination any client should name
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("0.0.0.0"));
        assertTrue(I2PTunnelHTTPServer.isLocalRequestHost("::"));
    }

    @Test
    public void publicAddressesAndNamesAreNotLocal() {
        assertFalse(I2PTunnelHTTPServer.isLocalRequestHost("8.8.8.8"));
        assertFalse(I2PTunnelHTTPServer.isLocalRequestHost("2001:db8::1"));
        assertFalse(I2PTunnelHTTPServer.isLocalRequestHost("example.com"));
        assertFalse(I2PTunnelHTTPServer.isLocalRequestHost("tracker.example.com"));
    }

    @Test
    public void emptyOrMalformedHostIsNotLocal() {
        assertFalse(I2PTunnelHTTPServer.isLocalRequestHost(null));
        assertFalse(I2PTunnelHTTPServer.isLocalRequestHost(""));
        assertFalse(I2PTunnelHTTPServer.isLocalRequestHost("1.2.3.4.evil"));
        assertFalse(I2PTunnelHTTPServer.isLocalRequestHost("999.1.1.1"));
    }
}
