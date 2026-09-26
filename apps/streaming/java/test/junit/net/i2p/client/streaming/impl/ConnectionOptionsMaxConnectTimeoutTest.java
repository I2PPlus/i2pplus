package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import java.util.Properties;

import org.junit.Test;

import net.i2p.client.streaming.I2PSocketOptions;

/**
 * Tests for the per-connection max connect timeout override
 * ({@link I2PSocketOptions#PROP_MAX_CONNECT_TIMEOUT}).
 * This is what lets per-tunnel client options (e.g. the IRC client tunnel)
 * raise the absolute cap on the SYN-ACK wait window above the router-wide
 * i2p.streaming.maxConnectTimeout default.
 */
public class ConnectionOptionsMaxConnectTimeoutTest {

    @Test
    public void testDefaultUsesGlobal() {
        ConnectionOptions opts = new ConnectionOptions();
        assertEquals("default must mean 'use global', i.e. 0", 0, opts.getMaxConnectTimeout());
    }

    @Test
    public void testSetAndGet() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.setMaxConnectTimeout(300000);
        assertEquals(300000, opts.getMaxConnectTimeout());
        opts.setMaxConnectTimeout(0);
        assertEquals("0 resets to 'use global'", 0, opts.getMaxConnectTimeout());
    }

    @Test
    public void testParseFromProperties() {
        Properties props = new Properties();
        props.setProperty(I2PSocketOptions.PROP_MAX_CONNECT_TIMEOUT, "300000");
        ConnectionOptions opts = new ConnectionOptions();
        opts.setProperties(props);
        assertEquals(300000, opts.getMaxConnectTimeout());
    }

    @Test
    public void testParseIgnoresAbsentProperty() {
        Properties props = new Properties();
        ConnectionOptions opts = new ConnectionOptions();
        opts.setMaxConnectTimeout(120000);
        opts.setProperties(props);
        assertEquals("absent property must not reset the override", 120000, opts.getMaxConnectTimeout());
    }

    @Test
    public void testParseIgnoresNonPositiveValue() {
        Properties props = new Properties();
        props.setProperty(I2PSocketOptions.PROP_MAX_CONNECT_TIMEOUT, "0");
        ConnectionOptions opts = new ConnectionOptions();
        opts.setMaxConnectTimeout(120000);
        opts.setProperties(props);
        assertEquals("0 must not be applied as an override", 120000, opts.getMaxConnectTimeout());
    }

    @Test
    public void testConstructFromPropertiesWithOverride() {
        Properties props = new Properties();
        props.setProperty(I2PSocketOptions.PROP_MAX_CONNECT_TIMEOUT, "300000");
        ConnectionOptions opts = new ConnectionOptions(props);
        assertEquals(300000, opts.getMaxConnectTimeout());
    }

    @Test
    public void testCopyDeep() {
        ConnectionOptions src = new ConnectionOptions();
        src.setMaxConnectTimeout(300000);
        ConnectionOptions copy = new ConnectionOptions(src);
        assertEquals("deep copy must carry the override", 300000, copy.getMaxConnectTimeout());
    }

    @Test
    public void testUpdateAllCopiesOverride() {
        ConnectionOptions src = new ConnectionOptions();
        src.setMaxConnectTimeout(300000);
        ConnectionOptions dst = new ConnectionOptions();
        dst.updateAll(src);
        assertEquals("updateAll must carry the override", 300000, dst.getMaxConnectTimeout());
    }

    @Test
    public void testEffectiveWindowRespectsRaisedCap() {
        // IRC default: connectDelay 150 + connectTimeout 180000, cap 300000
        // Base is already above the global 75000 default, so at multiplier 100
        // the old global cap would clamp to 75000 and the new override must not.
        long base = 150 + 180000;
        long effective = Connection.computeEffectiveConnectTimeout(base, 100, 300000);
        assertEquals("base below cap passes through at multiplier 100", base, effective);
    }

    @Test
    public void testEffectiveWindowScalesMultiplierWithinRaisedCap() {
        // At multiplier 200 (Tuner max) the IRC base of 180150 scales to 360300,
        // which exceeds the 300000 per-connection cap and must be pinned there.
        long base = 150 + 180000;
        long effective = Connection.computeEffectiveConnectTimeout(base, 200, 300000);
        assertEquals(300000, effective);
    }

    @Test
    public void testGlobalCapStillAppliesWithoutOverride() {
        // Without any per-connection override the 75000 default cap is used,
        // so a long connectTimeout stays clamped to it.
        long effective = Connection.computeEffectiveConnectTimeout(240000, 100, 75000);
        assertEquals(75000, effective);
    }
}
