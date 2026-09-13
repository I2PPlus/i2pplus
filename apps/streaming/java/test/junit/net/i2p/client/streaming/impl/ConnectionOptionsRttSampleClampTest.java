package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests RTT sample clamping and the RTO-doubling guard floor.
 *
 * <p>A single pathological sample must not pin smoothed RTT (and therefore the
 * RTO recovery cadence) at the ceiling, so raw samples are clamped before they
 * enter the smoother in {@link ConnectionOptions#updateRTT(int)} via
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

    /** updateRTT clamps before the smoother, so a huge single sample only
        reaches the ceiling in the smoothed RTT; the min-RTT tracker sees the
        raw sample so a slow path (> maxRtt) gets no fabricated floor. */
    @Test
    public void testUpdateRTTClampsAtIngestion() {
        ConnectionOptions opts = new ConnectionOptions();
        opts.updateRTT(100000);
        assertTrue("smoothed RTT must not absorb a pathological sample",
                   opts.getRTT() <= 60000);
        assertEquals("min RTT must track the raw sample, not the fabricated clamp",
                     100000, opts.getMinRTT());
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

    /**
     * Backoff must never shrink the timer. loadFromCache seeds an RTO in the
     * computeRTO ceiling band (12s &lt; RTO &lt;= 30s) which only computeRTO() can
     * reach; a bare {@code min(doubled, getMaxRTO())} clamp there would drag the
     * timer DOWN to 12s exactly when recovery wants it growing.
     */
    @Test
    public void testDoubleRTONeverShrinksAboveCap() throws InterruptedException {
        ConnectionOptions opts = new ConnectionOptions();
        // RTO = smoothed(500) + 4 * dev(7500) = 30500 -> clamped to maxResendDelay (30s)
        opts.loadFromCache(500, 7500, 8);
        int rto = opts.getRTO();
        assertTrue("require the shrink-hazard band, got " + rto,
                   rto > 12000 && rto <= 30000);
        for (int i = 0; i < 3; i++) {
            int next = opts.doubleRTO();
            assertTrue("backoff must never shrink the RTO: " + next + " < " + rto,
                       next >= rto);
            rto = next;
            Thread.sleep(ConnectionOptions.MIN_RTO_DOUBLE_GAP_MS + 50);
        }
        assertTrue("RTO must stay in the hazard band, not fall to the cap",
                   rto > 12000);
    }

    /** The floor constant is positive and sane. */
    @Test
    public void testFloorConstant() {
        assertTrue(ConnectionOptions.MIN_RTO_DOUBLE_GAP_MS > 0);
        assertTrue(ConnectionOptions.MIN_RTO_DOUBLE_GAP_MS <= 2000);
    }
}
