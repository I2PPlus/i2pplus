package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pool-owned adaptive test-window policy in
 * {@link TunnelPool#updatedAvgTestDuration(long, long)},
 * {@link TunnelPool#decayedAvgTestDuration(long)} and
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

    /**
     * Holds nothing: every case feeds the policy helpers their own average,
     * sample and multiplier.
     */
    public TunnelPoolAdaptiveTestWindowTest() {
    }

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
        // At most half: the fraction deliberately equals the isMateriallyShort
        // line at 0.5 (pinned by TunnelPoolPreEmergencyTest), and beyond half
        // the burst would fire against pools that are barely thin.
        assertTrue(TunnelPool.PRE_EMERGENCY_FRACTION <= 0.5);
    }

    /**
     * A round that never answered is timed out at the window, so its duration
     * <em>is</em> the window. Read as a latency it holds the average above
     * {@link TunnelPool#SLOW_TEST_AVG_MS} and the window at its widened ceiling
     * - the same loop that pinned the test window on 2026-11-25. Pulling the
     * average down instead gives the window a way back.
     */
    @Test
    public void testSilentRoundPullsTheAverageDown() {
        long avg = 9000;
        long decayed = TunnelPool.decayedAvgTestDuration(avg);
        assertTrue("a silent round must move the average: " + decayed, decayed < avg);
        assertEquals((long)(avg * TunnelPool.DURATION_SMOOTHING), decayed);
        for (int i = 0; i < 100; i++) {
            decayed = TunnelPool.decayedAvgTestDuration(decayed);
        }
        assertEquals("repeated silence reaches the floor", 0L, decayed);
        assertEquals("and a 1ms average does not stick there", 0L,
                     TunnelPool.decayedAvgTestDuration(1));
    }

    /** No average, and a negative one, decay to nothing - never to a negative duration. */
    @Test
    public void testDecayCannotGoNegative() {
        assertEquals(0L, TunnelPool.decayedAvgTestDuration(0));
        assertEquals(0L, TunnelPool.decayedAvgTestDuration(-5));
    }

    /**
     * The decay and the smoother pull the same distance, so the difference
     * between a silent round and an instant one is what the multiplier learns
     * from the average, not how hard either one moves it.
     */
    @Test
    public void testDecayMatchesTheSmootherStep() {
        assertEquals(TunnelPool.updatedAvgTestDuration(9000, 0),
                     TunnelPool.decayedAvgTestDuration(9000));
    }

    /**
     * The consequence the change exists for: a pool whose rounds time out keeps
     * its window at the ceiling while their deadlines are being fed in as
     * latency, and walks it back to the floor once they are read as silence.
     */
    @Test
    public void testSilentRoundsNarrowWhatTimedLatencyWouldKeepWide() {
        double widened = 1.0;
        long avg = TunnelPool.updatedAvgTestDuration(0, 15000);
        for (int i = 0; i < 10; i++) {
            avg = TunnelPool.updatedAvgTestDuration(avg, 15000);
            widened = TunnelPool.updatedAdaptiveTestMultiplier(widened, avg);
        }
        assertEquals("a deadline fed as latency pins the window at the cap",
                     TunnelPool.MAX_ADAPTIVE_TEST_MULTIPLIER, widened, 0.0001);

        double narrowed = TunnelPool.MAX_ADAPTIVE_TEST_MULTIPLIER;
        long silent = 15000;
        for (int i = 0; i < 60; i++) {
            silent = TunnelPool.decayedAvgTestDuration(silent);
            narrowed = TunnelPool.updatedAdaptiveTestMultiplier(narrowed, silent);
        }
        assertEquals("silence walks the window back to the floor", 1.0, narrowed, 0.0001);
    }
}
