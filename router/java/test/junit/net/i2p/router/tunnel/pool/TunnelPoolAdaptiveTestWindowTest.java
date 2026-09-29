package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pool-owned adaptive test-window policy in
 * {@link TunnelPool#updatedAvgTestDuration(long, long)} and
 * {@link TunnelPool#updatedAdaptiveTestMultiplier(double, long)}.
 *
 * <p>These lived on {@link TestJob}, which is constructed per build-request
 * batch and discarded, so the average and multiplier reset to their initial
 * values before they could ever accumulate: the adaptation was inert. The
 * state now lives on the long-lived pool, and these tests pin the policy it
 * applies.
 *
 * <p>Pure, so no router is required.
 *
 * @since 0.9.71+
 */
public class TunnelPoolAdaptiveTestWindowTest {

    /** The first sample seeds the average outright. */
    @Test
    public void testFirstSampleSeedsAverage() {
        assertEquals(3000L, TunnelPool.updatedAvgTestDuration(0, 3000));
    }

    /** A negative or absent sample cannot poison the average. */
    @Test
    public void testDegenerateFirstSample() {
        assertEquals(0L, TunnelPool.updatedAvgTestDuration(0, -5));
    }

    /** Subsequent samples move the average toward the new value, weighted 0.1. */
    @Test
    public void testAverageIsSmoothed() {
        // 1000*0.9 + 3000*0.1 = 900 + 300 = 1200
        long avg = TunnelPool.updatedAvgTestDuration(1000, 3000);
        assertEquals(1200L, avg);
    }

    /**
     * The multiplier is floored at 1.0, so a fast average cannot shrink a
     * window that is already at the floor. It only decays a window that was
     * previously widened.
     */
    @Test
    public void testFastTestsNarrowOnlyAnAlreadyWidenedWindow() {
        assertEquals("already at the floor, stays there",
                     1.0, TunnelPool.updatedAdaptiveTestMultiplier(1.0, 500), 0.0001);
        // 5000ms is above SLOW_TEST_AVG_MS, so it widens: 1.25 * 1.25.
        assertEquals(1.5625, TunnelPool.updatedAdaptiveTestMultiplier(1.25, 5000), 0.0001);
        double decayed = TunnelPool.updatedAdaptiveTestMultiplier(1.25, 500);
        assertTrue("a widened window must shrink again: " + decayed, decayed < 1.25);
        assertTrue("but never below 1.0: " + decayed, decayed >= 1.0);
    }

    /**
     * A slow average widens the window, but the cap is what stops a dead tunnel
     * from being masked indefinitely.
     */
    @Test
    public void testSlowTestsWidenWindowUpToCap() {
        assertEquals(1.25, TunnelPool.updatedAdaptiveTestMultiplier(1.0, 5000), 0.0001);
        double m = 1.0;
        for (int i = 0; i < 20; i++) {
            m = TunnelPool.updatedAdaptiveTestMultiplier(m, 5000);
        }
        assertEquals("multiplier must be capped",
                     TunnelPool.MAX_ADAPTIVE_TEST_MULTIPLIER, m, 0.0001);
    }

    /** A mid-range average leaves the window alone. */
    @Test
    public void testMidRangeAverageLeavesWindowUnchanged() {
        assertEquals(1.5, TunnelPool.updatedAdaptiveTestMultiplier(1.5, 1500), 0.0001);
    }

    /** The cap itself is a real bound, not a growing target. */
    @Test
    public void testCapIsFinite() {
        assertTrue(TunnelPool.MAX_ADAPTIVE_TEST_MULTIPLIER > 1.0);
        assertTrue(TunnelPool.MAX_ADAPTIVE_TEST_MULTIPLIER <= 2.0);
    }

    /** The pre-emergency burst is a named constant, not a bare literal. */
    @Test
    public void testPreEmergencyBurstIsNamed() {
        assertTrue(TunnelPool.PRE_EMERGENCY_BURST > 0);
        assertTrue(TunnelPool.PRE_EMERGENCY_FRACTION > 0.0);
        assertTrue(TunnelPool.PRE_EMERGENCY_FRACTION < 0.5);
    }
}
