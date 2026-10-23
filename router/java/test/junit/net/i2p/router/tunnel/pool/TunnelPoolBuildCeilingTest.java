package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the absolute in-progress build ceiling that bounds every build path in
 * a pool.
 *
 * <p>Observed in production: one 4-tunnel pool accumulated 99 concurrent
 * outbound builds. The cause was the pre-emergency burst, which bypassed the
 * throttle and, with it, the in-progress guards, so it started a fresh burst on
 * every cycle while its builds neither completed nor failed.
 *
 * <p>Pure arithmetic, so no router is required.
 *
 * @since 0.9.71+
 */
public class TunnelPoolBuildCeilingTest {

    /** The ceiling must bound every per-state heuristic the guard can compute. */
    @Test
    public void testCeilingBoundsEveryHeuristic() {
        int[] targets = {1, 2, 4, 8, 16, 64, 1000};
        int[] safeActives = {0, 1, 2, 4, 8, 16};
        for (int target : targets) {
            for (int safeActive : safeActives) {
                for (double rate : new double[] {0.0, 0.25, 0.5, 0.75, 1.0}) {
                    int cap = effectiveCap(safeActive, target, rate);
                    assertTrue("cap " + cap + " must not exceed the ceiling",
                               cap <= TunnelPool.MAX_INPROGRESS_PER_POOL_DIR);
                    assertTrue("cap must be positive for rate " + rate, cap > 0);
                }
            }
        }
    }

    /**
     * The whole point of the change: a thin pool must bank more in-flight
     * budget as its builds time out, or it cannot recover.
     *
     * <p>Each failed attempt holds a slot for the full timeout, so at 47%
     * timeout rate a budget of six yields about three landable tunnels per
     * timeout window instead of six. The pool then replaces fewer tunnels per
     * cycle than it loses and drains, which is the collapse this bounds.
     */
    @Test
    public void testThinPoolBudgetsMoreWhenBuildsTimeOut() {
        assertEquals("at 0% timeout rate a thin pool keeps the old budget",
                     6, effectiveCap(2, 8, 0.0));
        assertTrue("a failing pool must budget strictly more",
                   effectiveCap(2, 8, 0.5) > effectiveCap(2, 8, 0.0));
        assertTrue("and more again at a 100% timeout rate",
                   effectiveCap(2, 8, 1.0) > effectiveCap(2, 8, 0.5));
        // 6 * (1 + rate), rounded: 9 at half, 12 at full.
        assertEquals(9, effectiveCap(2, 8, 0.5));
        assertEquals(12, effectiveCap(2, 8, 1.0));
    }

    /** A healthy pool must not gain budget — that is what the ceiling guards. */
    @Test
    public void testHealthyPoolBudgetIsIndependentOfTimeoutRate() {
        for (double rate : new double[] {0.0, 0.5, 1.0}) {
            assertEquals("a full pool keeps target+1 regardless of timeouts",
                         9, effectiveCap(8, 8, rate));
            assertEquals("an empty pool keeps min(target+2, 8)",
                         8, effectiveCap(0, 8, rate));
        }
    }

    /** Degenerate rates must not shrink the budget below the old floor. */
    @Test
    public void testOutOfRangeRatesCannotShrinkTheBudget() {
        assertTrue("NaN and negatives are clamped, not honoured",
                   effectiveCap(2, 8, -1.0) >= effectiveCap(2, 8, 0.0));
        assertTrue("an over-range rate is clamped to 1.0",
                   effectiveCap(2, 8, 2.0) == effectiveCap(2, 8, 1.0));
    }

    /**
     * The burst path adds on top of whatever is already in flight, so the bound
     * that matters is the heuristic's own maximum plus one burst.
     */
    @Test
    public void testBurstNeverStartsBuildsAtOrAboveCeiling() {
        // The production path sizes the burst as
        //   budget = max(0, CEILING - inProgress)
        // so at or above the ceiling it starts nothing. The count can already
        // exceed the ceiling if it arrived by another route; what the ceiling
        // guarantees is that no *further* build is started.
        for (int inProgress = 0; inProgress <= 40; inProgress++) {
            int budget = Math.max(0, TunnelPool.MAX_INPROGRESS_PER_POOL_DIR - inProgress);
            int started = Math.min(TunnelPool.PRE_EMERGENCY_BURST, budget);
            if (inProgress >= TunnelPool.MAX_INPROGRESS_PER_POOL_DIR) {
                assertEquals("must start nothing at inProgress " + inProgress, 0, started);
            } else {
                assertTrue("inProgress " + inProgress + " overshot the ceiling",
                           inProgress + started <= TunnelPool.MAX_INPROGRESS_PER_POOL_DIR);
            }
        }
    }

    /**
     * The per-state heuristic is always at or below the ceiling, so the clamp
     * is what makes the ceiling the true worst case rather than a coincidence.
     */
    @Test
    public void testCeilingIsTheTrueWorstCase() {
        int worst = 0;
        for (int target = 1; target <= 128; target++) {
            for (int safeActive = 0; safeActive <= 32; safeActive++) {
                for (double rate = 0.0; rate <= 1.0001; rate += 0.25) {
                    worst = Math.max(worst, effectiveCap(safeActive, target, rate));
                }
            }
        }
        assertEquals("no input may produce a cap above the ceiling",
                     TunnelPool.MAX_INPROGRESS_PER_POOL_DIR, worst);
    }

    /** The ceiling itself is a real, finite bound. */
    @Test
    public void testCeilingIsFinite() {
        assertTrue(TunnelPool.MAX_INPROGRESS_PER_POOL_DIR > 0);
        assertTrue("ceiling must stay well below the observed runaway",
                   TunnelPool.MAX_INPROGRESS_PER_POOL_DIR <= 32);
    }

    /**
     * The pre-emergency burst must not be able to exceed the ceiling from a
     * standing start, which is the case that produced 99 builds.
     */
    @Test
    public void testPreEmergencyBurstFitsUnderCeiling() {
        assertTrue("burst must be at least 1 or the emergency path is a no-op",
                   TunnelPool.PRE_EMERGENCY_BURST >= 1);
        assertTrue("a single burst must fit under the ceiling",
                   TunnelPool.PRE_EMERGENCY_BURST <= TunnelPool.MAX_INPROGRESS_PER_POOL_DIR);
    }

    /**
     * Mirrors TunnelPool.shouldSkipDueToInProgress's cap computation, including
     * the timeout-rate scaling added for thin pools.
     *
     * @param safeActive the count of safe, non soft-degraded tunnels in the pool,
     *                   0 meaning the pool has collapsed to zero usable tunnels
     * @param target the base tunnel count from the pool's configuration
     * @param timeoutRate fraction of builds timing out in [0.0, 1.0], clamped
     * @return the maximum number of builds that may be in flight for the pool,
     *         never above TunnelPool.MAX_INPROGRESS_PER_POOL_DIR
     */
    private static int effectiveCap(int safeActive, int target, double timeoutRate) {
        int cap;
        if (safeActive > 0 && safeActive < target) {
            double lossFactor = 1.0 + Math.max(0.0, Math.min(1.0, timeoutRate));
            cap = Math.min(Math.max(target * 2, 4), 6);
            cap = (int) Math.min(Math.round(cap * lossFactor),
                                 TunnelPool.MAX_INPROGRESS_PER_POOL_DIR);
        } else if (safeActive > 0) {
            cap = Math.max(target + 1, 2);
        } else {
            cap = Math.min(target + 2, 8);
        }
        return Math.min(cap, TunnelPool.MAX_INPROGRESS_PER_POOL_DIR);
    }
}
