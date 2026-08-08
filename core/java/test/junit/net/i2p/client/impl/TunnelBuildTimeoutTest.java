package net.i2p.client.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Test the tunnel build timeout parsing in I2PSessionImpl.
 *
 * @since 0.9.71+
 */
public class TunnelBuildTimeoutTest {

    @Test
    public void testDefaults() {
        assertEquals(20, I2PSessionImpl.getTunnelBuildTimeout(null, null));
        assertEquals(20, I2PSessionImpl.getTunnelBuildTimeout(null, ""));
    }

    @Test
    public void testSessionValueWins() {
        assertEquals(3, I2PSessionImpl.getTunnelBuildTimeout("3", "20"));
        assertEquals(5, I2PSessionImpl.getTunnelBuildTimeout(" 5 ", null));
    }

    @Test
    public void testContextFallback() {
        assertEquals(7, I2PSessionImpl.getTunnelBuildTimeout(null, "7"));
    }

    @Test
    public void testInvalidValues() {
        assertEquals(20, I2PSessionImpl.getTunnelBuildTimeout("0", null));
        assertEquals(20, I2PSessionImpl.getTunnelBuildTimeout("-1", null));
        assertEquals(20, I2PSessionImpl.getTunnelBuildTimeout("abc", null));
        assertEquals(20, I2PSessionImpl.getTunnelBuildTimeout("", "abc"));
    }

    @Test
    public void testLargeValue() {
        assertEquals(100000, I2PSessionImpl.getTunnelBuildTimeout("100000", null));
    }
}