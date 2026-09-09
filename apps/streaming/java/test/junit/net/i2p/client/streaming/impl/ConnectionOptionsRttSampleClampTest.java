package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests RTT sample clamping and the RTO-doubling guard floor.
 *
 * <p>A single pathological sample must not pin smoothed RTT (and therefore the
 * RTO recovery cadence) at the ceiling, so raw samples are clamped at ingestion
 * in {@link ConnectionOptions#updateRTT(int)} via
 * {@link ConnectionOptions#clampRttSample(int, int)}. The once-per-RTT guard in
 * {@link ConnectionOptions#doubleRTO()} is floored by
 * {@link ConnectionOptions#MIN_RTO_DOUBLE_GAP_MS} so a degenerate smoothed RTT
 * cannot turn the guard into a no-op.
 *
 * @since 0.9.72
 */
public class ConnectionOptionsRttSampleClampTest {

    /** Samples within the ceiling pass through untouched. */
    @Test
    public void testSampleBelowMaxUnchanged() {
        assertEquals(500, ConnectionOptions.clampRttSample(500, 60000));
        assertEquals(60000, ConnectionOptions.clampRttSample(60000, 60000));
    }

    /** Samples above the ceiling are reined in. */
    @Test
    public void testSampleAboveMaxClipped() {
        assertEquals(60000, ConnectionOptions.clampRttSample(100000, 60000));
        assertEquals(60000, ConnectionOptions.clampRttSample(60001, 60000));
    }

    /** Negative/zero samples (sentinel -1) must not be mangled to a positive value. */
    @Test
    public void testNegativeSampleNotTurnedPositive() {
        assertEquals(0, ConnectionOptions.clampRttSample(-1, 60000));
        assertEquals(0, ConnectionOptions.clampRttSample(-500, 60000));
        assertEquals(0, ConnectionOptions.clampRttSample(0, 60000));
    }

    /** A non-positive ceiling disables clamping (caller's sentinel handling preserved). */
    @Test
    public void testMutualExclusionOfCeiling() {
        assertEquals(100000, ConnectionOptions.clampRttSample(100000, 0));
        assertEquals(-1, ConnectionOptions.clampRttSample(-1, 0));
        assertEquals(500, ConnectionOptions.clampRttSample(500, -1));
    }

    /** updateRTT clamps at ingestion, so a huge single sample only reaches the ceiling. */
    @Test
    public void testUpdateRTTClampsAtIngestion() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(100000);
        assertTrue("smoothed RTT must not absorb a pathological sample",
                   opts.getRTT() <= 60000);
        // And the min-RTT tracker sees the same clamped value, not the raw one.
        assertTrue("min RTT must agree with the clamped sample",
                   opts.getMinRTT() <= 60000);
    }

    /**
     * The once-per-RTT doubling guard uses a floor of MIN_RTO_DOUBLE_GAP_MS:
     * with a degenerate 50ms smoothed RTT and a 200ms gap, a second doubleRTO()
     * must be suppressed (old behavior would have doubled again past the 50ms
     * RTT-sized gap).
     */
    @Test
    public void testDoubleRTORespectsFloorGap() throws InterruptedException {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(50);
        int first = opts.doubleRTO();
        Thread.sleep(200);
        int second = opts.doubleRTO();
        assertEquals("doubling must be limited to once per floored gap, not once per RTT",
                     first, second);
    }

    /** The floor constant is positive and sane. */
    @Test
    public void testFloorConstant() {
        assertTrue(ConnectionOptions.MIN_RTO_DOUBLE_GAP_MS > 0);
        assertTrue(ConnectionOptions.MIN_RTO_DOUBLE_GAP_MS <= 2000);
    }
}
