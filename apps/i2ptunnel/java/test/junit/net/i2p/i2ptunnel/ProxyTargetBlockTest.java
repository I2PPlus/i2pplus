package net.i2p.i2ptunnel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the proxy target blocklist in I2PTunnelHTTPClientBase.
 * Non-.i2p targets are handed to the outproxy plugin, which opens a raw
 * socket from this machine, so a request for 127.0.0.1 or 10.x would
 * otherwise reach the router itself or the LAN.
 *
 * @since 0.9.71+
 */
public class ProxyTargetBlockTest {

    @Test
    public void testNullAndBlankBlocked() {
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress(null));
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress(""));
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress("   "));
    }

    @Test
    public void testLocalhostNames() {
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress("localhost"));
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress("LOCALHOST"));
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress("foo.localhost"));
        assertFalse(I2PTunnelHTTPClientBase.isBlockedLocalAddress("localho.st"));
        assertFalse(I2PTunnelHTTPClientBase.isBlockedLocalAddress("localhost.example.com"));
    }

    @Test
    public void testPrivateAndReservedIpv4Blocked() {
        String[] blocked = {
            "127.0.0.1", "127.1.2.3", "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.255",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.0.1",
            "192.0.2.5", "198.18.0.1", "198.51.100.7", "203.0.113.9"
        };
        for (String host : blocked)
            assertTrue(host, I2PTunnelHTTPClientBase.isBlockedLocalAddress(host));
    }

    @Test
    public void testPublicIpv4Allowed() {
        String[] allowed = {
            "8.8.8.8", "1.2.3.4", "11.22.33.44", "172.15.255.255", "172.32.0.1",
            "100.63.255.255", "100.128.0.1", "192.0.1.1", "198.52.100.1"
        };
        for (String host : allowed)
            assertFalse(host, I2PTunnelHTTPClientBase.isBlockedLocalAddress(host));
    }

    @Test
    public void testAmbiguousLiteralsBlocked() {
        // digits and dots, but not a strict dotted quad, cannot be a name
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress("127.1"));
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress("2130706433"));
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress("999.1.1.1"));
        assertTrue(I2PTunnelHTTPClientBase.isBlockedLocalAddress("1.2.3.4.5"));
        assertTrue(I2PTunnelHTTPClientBase.isBlockedIPv4Address("192.168.0.256"));
        assertTrue(I2PTunnelHTTPClientBase.isBlockedIPv4Address("172.31"));
        // names, including ones that look like private addresses, are not
        assertFalse(I2PTunnelHTTPClientBase.isBlockedLocalAddress("example.com"));
        assertFalse(I2PTunnelHTTPClientBase.isBlockedLocalAddress("10.0.example.com"));
        assertFalse(I2PTunnelHTTPClientBase.isBlockedLocalAddress("10.0.0.1.example.com"));
    }

    @Test
    public void testIpv6Blocked() {
        String[] blocked = {
            "::1", "0:0:0:0:0:0:0:1", "::", "::ffff:127.0.0.1", "[::1]",
            "[::ffff:10.0.0.1]", "fe80::1", "[fe80::1]", "fe80::1%eth0",
            "fc00::1", "fd12:3456:789a::1", "ff02::1"
        };
        for (String host : blocked)
            assertTrue(host, I2PTunnelHTTPClientBase.isBlockedLocalAddress(host));
    }

    @Test
    public void testIpv6Allowed() {
        String[] allowed = {
            "2001:4860:4860::8888", "::ffff:8.8.8.8", "[2606:4700::1111]",
            "2001:db8::1"
        };
        for (String host : allowed)
            assertFalse(host, I2PTunnelHTTPClientBase.isBlockedLocalAddress(host));
    }
}
