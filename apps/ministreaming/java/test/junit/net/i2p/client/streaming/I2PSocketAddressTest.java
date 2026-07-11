package net.i2p.client.streaming;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for I2PSocketAddress value object.
 *
 * @since 0.9.70+
 */
public class I2PSocketAddressTest {

    @Test(expected = IllegalArgumentException.class)
    public void testNegativePort() {
        I2PSocketAddress.createUnresolved("example.i2p", -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPortTooHigh() {
        I2PSocketAddress.createUnresolved("example.i2p", 65536);
    }

    @Test
    public void testValidPort() {
        I2PSocketAddress addr = I2PSocketAddress.createUnresolved("example.i2p", 8080);
        assertEquals(8080, addr.getPort());
    }

    @Test
    public void testZeroPort() {
        I2PSocketAddress addr = I2PSocketAddress.createUnresolved("example.i2p", 0);
        assertEquals(0, addr.getPort());
    }

    @Test
    public void testCreateUnresolved() {
        I2PSocketAddress addr = I2PSocketAddress.createUnresolved("example.i2p", 80);
        assertEquals(80, addr.getPort());
        assertEquals("example.i2p", addr.getHostName());
        assertTrue(addr.isUnresolved());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateUnresolvedNegativePort() {
        I2PSocketAddress.createUnresolved("example.i2p", -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateUnresolvedPortTooHigh() {
        I2PSocketAddress.createUnresolved("example.i2p", 65536);
    }

    @Test
    public void testEqualsUnresolved() {
        I2PSocketAddress a = I2PSocketAddress.createUnresolved("example.i2p", 80);
        I2PSocketAddress b = I2PSocketAddress.createUnresolved("example.i2p", 80);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testNotEqualsDifferentPort() {
        I2PSocketAddress a = I2PSocketAddress.createUnresolved("example.i2p", 80);
        I2PSocketAddress b = I2PSocketAddress.createUnresolved("example.i2p", 443);
        assertNotEquals(a, b);
    }

    @Test
    public void testNotEqualsDifferentHost() {
        I2PSocketAddress a = I2PSocketAddress.createUnresolved("example.i2p", 80);
        I2PSocketAddress b = I2PSocketAddress.createUnresolved("other.i2p", 80);
        assertNotEquals(a, b);
    }

    @Test
    public void testNotEqualsNull() {
        I2PSocketAddress a = I2PSocketAddress.createUnresolved("example.i2p", 80);
        assertNotEquals(null, a);
    }

    @Test
    public void testToString() {
        I2PSocketAddress addr = I2PSocketAddress.createUnresolved("example.i2p", 80);
        String s = addr.toString();
        assertTrue(s.contains("example.i2p"));
        assertTrue(s.contains("80"));
    }
}
