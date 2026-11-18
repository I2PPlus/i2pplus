package net.i2p.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests that the transit rejection curve measures fullness against the
 * bandwidth a router offers to participating tunnels, not against its whole
 * bandwidth limit.
 *
 * <p>The throttle bounds what is available by the configured network share and
 * then asks how full the router is. Ask that second question of the unshared
 * limit and a router at a fifth of its own allowance already reads as nearly
 * full, the curve engages, and transit build requests are refused with room
 * left - and the refusal is what the remote peer remembers, as this router
 * being bandwidth-starved. At the default 90 % share the total-limit reserve is
 * the smaller of the two ceilings, so a stock router is unaffected.
 *
 * <p>Arithmetic only: the decision is driven through the same three steps
 * allowTunnel() takes, over a 64 KB/s limit, where the unshared ceiling is
 * 54,886 B/s.
 *
 * @since 0.9.72+
 */
public class ThrottleShareCurveTest {

    /** The bandwidth limit the figures here are computed against. */
    private static final int MAX_KBPS = 64;

    /** The rejection steepness the throttle applies unless the Tuner sets one. */
    private static final int EXPONENT = 10;

    /**
     * A fixture group for the throttle's curve; it holds no state.
     *
     * @since 0.9.72+
     */
    public ThrottleShareCurveTest() {}

    /**
     * At the default 90 % share the ceiling is still the total limit less its
     * 4 KB/s reserve, so the change is a no-op for a stock router.
     */
    @Test
    public void testTheDefaultShareKeepsTheOldCeiling() {
        float reserveCeiling = (float) ((MAX_KBPS * 1024f * 0.9f) - 4 * 1024);
        assertEquals(reserveCeiling, RouterThrottleImpl.sharedCeilingBps(MAX_KBPS, 0.9d), 0.1f);
    }

    /**
     * Below that share the allowance is the ceiling: 10 % of a 64 KB/s limit is
     * 6,554 B/s, not the 54,886 B/s the total-limit reserve gives.
     */
    @Test
    public void testASmallShareBoundsTheCeiling() {
        assertEquals(6553.6f, RouterThrottleImpl.sharedCeilingBps(MAX_KBPS, 0.1d), 0.1f);
    }

    /**
     * A router using four fifths of a 10 % share is nowhere near full: the curve
     * gives a probability under 1 %, where the unshared ceiling put it at 37 %.
     */
    @Test
    public void testALightlyUsedShareIsNotNearlyFull() {
        double prob = probabilityAt(0.1d, 5243);
        assertTrue("a share used four fifths rejected at " + prob, prob < 0.01d);
    }

    /**
     * An idle router on a 5 % share rejects nothing at all. Against the whole
     * limit it read as 94 % full and refused about half of the transit requests
     * it received while carrying no traffic whatsoever.
     */
    @Test
    public void testAnIdleRouterOnATinyShareRejectsNothing() {
        int avail = (int) (MAX_KBPS * 1024 * 0.05d);
        assertEquals(0d, probabilityAt(0.05d, avail), 0.0001d);
    }

    /**
     * A used-up allowance still rejects outright - the fix is the denominator,
     * not a raised threshold.
     */
    @Test
    public void testAUsedUpAllowanceStillRejects() {
        assertEquals(1d, probabilityAt(0.9d, 0), 0.0001d);
        assertEquals(1d, probabilityAt(0.1d, 0), 0.0001d);
    }

    /**
     * The curve itself is untouched: nothing below the threshold, and the
     * fullness raised to the configured exponent above it.
     */
    @Test
    public void testTheCurveThresholdAndSteepnessAreUnchanged() {
        assertEquals(0d, RouterThrottleImpl.rejectProbability(0.899f, EXPONENT), 0d);
        double atThreshold = RouterThrottleImpl.rejectProbability(0.9f, EXPONENT);
        assertEquals(Math.pow(0.9f, EXPONENT), atThreshold, 0.0001d);
        assertEquals(Math.pow(0.95f, 3), RouterThrottleImpl.rejectProbability(0.95f, 3), 0.0001d);
    }

    /**
     * A share so small it leaves no allowance at all rejects outright rather
     * than dividing by zero: the fraction is 1 whatever is available.
     */
    @Test
    public void testNoAllowanceRejectsOutright() {
        assertEquals(1f, RouterThrottleImpl.percentFull(1000, 0f), 0f);
        assertEquals(1f, RouterThrottleImpl.percentFull(1000, -384f), 0f);
        assertEquals(0.25f, RouterThrottleImpl.percentFull(3000, 4000f), 0.0001f);
    }

    /**
     * The three steps the throttle takes, in the same order.
     *
     * @param share the fraction of the limit offered to participating tunnels
     * @param availBps bandwidth unallocated at the point of the decision
     * @return the probability of refusing the request
     */
    private static double probabilityAt(double share, int availBps) {
        float ceiling = RouterThrottleImpl.sharedCeilingBps(MAX_KBPS, share);
        float pctFull = RouterThrottleImpl.percentFull(availBps, ceiling);
        return RouterThrottleImpl.rejectProbability(pctFull, EXPONENT);
    }
}
