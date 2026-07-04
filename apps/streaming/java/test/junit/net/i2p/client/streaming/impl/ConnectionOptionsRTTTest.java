package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for ConnectionOptions RTT/RTO calculations.
 * These directly impact retransmission timing and throughput.
 * RFC 6298 compliance is critical for good performance.
 */
public class ConnectionOptionsRTTTest {

    @Test
    public void testInitialRTT() {
        ConnectionOptions opts = new ConnectionOptions();
        assertEquals(ConnectionOptions.DEFAULT_INITIAL_RTT, opts.getRTT());
    }

    @Test
    public void testInitialRTO() {
        ConnectionOptions opts = new ConnectionOptions();
        assertTrue("Initial RTO should be positive", opts.getRTO() > 0);
    }

    @Test
    public void testFirstRTTUpdate() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(500);
        assertEquals("First RTT update should set RTT directly", 500, opts.getRTT());
    }

    @Test
    public void testFirstRTTUpdateSetsRTTDev() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(1000);
        assertEquals("RTTDev should be half of RTT after first update", 500, opts.getRTTDev());
    }

    @Test
    public void testSecondRTTUpdateSmooths() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(1000);
        opts.updateRTT(2000);
        // smoothed = (1 - 1/8) * 1000 + (1/8) * 2000 = 875 + 250 = 1125
        assertEquals("Smoothed RTT should be 1125", 1125, opts.getRTT());
    }

    @Test
    public void testRTTConverges() {
        ConnectionOptions opts = new ConnectionOptions();
        // Feed many samples at 500ms
        for (int i = 0; i < 50; i++) {
            opts.updateRTT(500);
        }
        assertEquals("RTT should converge to 500", 500, opts.getRTT());
    }

    @Test
    public void testRTTConvergesUpward() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(500);
        // Feed many samples at 2000ms
        for (int i = 0; i < 100; i++) {
            opts.updateRTT(2000);
        }
        assertTrue("RTT should converge toward 2000: " + opts.getRTT(),
                   opts.getRTT() >= 1900);
    }

    @Test
    public void testMinRTTTracking() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(1000);
        opts.updateRTT(500);
        opts.updateRTT(800);
        assertEquals("Min RTT should track minimum observed", 500, opts.getMinRTT());
    }

    @Test
    public void testRTOAfterSteadyState() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(1000);
        opts.updateRTT(1000);
        int rto = opts.getRTO();
        // RTO = rtt + kappa * rttDev = 1000 + 4 * rttDev
        assertTrue("RTO should be >= RTT", rto >= opts.getRTT());
    }

    @Test
    public void testDoubleRTO() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(1000);
        opts.updateRTT(1000);
        int before = opts.getRTO();
        int after = opts.doubleRTO();
        assertEquals("Double RTO should be 2x", before * 2, after);
    }

    @Test
    public void testDoubleRTOCapsAtMax() {
        ConnectionOptions opts = new ConnectionOptions();
        // Force RTO high then double until capped
        opts.updateRTT(60000);
        opts.updateRTT(60000);
        int prev;
        do {
            prev = opts.getRTO();
            opts.doubleRTO();
        } while (opts.getRTO() > prev);
        // After capping, RTO should not exceed 30000 (default MAX_RTO)
        assertTrue("RTO should be capped", opts.getRTO() <= 30000);
    }

    @Test
    public void testInitialWindowSize() {
        ConnectionOptions opts = new ConnectionOptions();
        assertEquals(ConnectionOptions.getInitialWindowSize(), opts.getWindowSize());
    }

    @Test
    public void testSetWindowSize() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.setWindowSize(32);
        assertEquals(32, opts.getWindowSize());
    }

    @Test
    public void testRTTCapsAtMax() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(100000); // absurdly high
        assertTrue("RTT should be capped", opts.getRTT() <= 60000);
    }

    @Test
    public void testReceivedAck() {
        ConnectionOptions opts = new ConnectionOptions();
        assertFalse("Before any ack, receivedAck should be false", opts.receivedAck());
        opts.updateRTT(500);
        assertTrue("After ack, receivedAck should be true", opts.receivedAck());
    }
}
