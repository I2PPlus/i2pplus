package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure decision tests for soft-degraded thrash guards:
 * {@link TunnelPool#computeDeficit(int, int, int, int, int, int, int, int)},
 * {@link TunnelPool#hasValidTunnelsBlockingEmergency(int)}, and
 * {@link TunnelPool#shouldDeferRemovalForRebuild(int, int)}.
 *
 * <p>Policy: a soft-degraded pool still occupies safe slots — deficit builds
 * only the shortfall to target, never a full empty-pool rebuild on top of a
 * full pool; EMERGENCY is deferred while any valid tunnels remain; non-dead
 * removals on a thin pool wait until replacements are staged so RemoveSlow
 * early-expiry plus send-fail bursts cannot drain capacity to zero.
 *
 * @since 0.9.71+
 */
public class TunnelPoolSoftDegradedDecisionTest {

    // ---------- computeDeficit ----------

    @Test
    public void testDeficitSoftDegradedFullPoolBuildsNothing() {
        // healthy=0, safeActive=6 (all soft-degraded), target=4: surplus,
        // and 6 is already at addTunnel's target + 2 cap — nothing to build
        assertEquals(0, TunnelPool.computeDeficit(0, 6, 6, 0, 0, 4, 4, 0));
        // in-progress or untested already cover any gap
        assertEquals(0, TunnelPool.computeDeficit(0, 6, 6, 0, 2, 4, 4, 1));
    }

    @Test
    public void testDeficitSoftDegradedPartialPoolBuildsShortfallOnly() {
        // healthy=0, safeActive=2 (both soft-degraded), effectiveTarget=6
        assertEquals(4, TunnelPool.computeDeficit(0, 2, 2, 0, 0, 4, 6, 0));
        // already building toward the gap
        assertEquals(2, TunnelPool.computeDeficit(0, 2, 2, 0, 0, 4, 6, 2));
        // untested count against the deficit
        assertEquals(1, TunnelPool.computeDeficit(0, 2, 2, 0, 1, 4, 6, 2));
    }

    @Test
    public void testDeficitEmptyPoolBuildsFullGap() {
        // healthy=0, safeActive=0: empty-pool formula (no failingBoost)
        assertEquals(6, TunnelPool.computeDeficit(0, 0, 0, 3, 0, 4, 6, 0));
        // bounded so timeouts cannot create a build storm
        assertEquals(0, TunnelPool.computeDeficit(0, 0, 0, 0, 4, 4, 6, 4));
        assertEquals(0, TunnelPool.computeDeficit(0, 0, 0, 0, 6, 4, 6, 2));
    }

    @Test
    public void testDeficitHealthyPoolUsesHealthyCountAndFailingBoost() {
        // healthy=3, failing=2, target=4: gap to 6 + min(failing, target)
        assertEquals(6 - 3 + 2, TunnelPool.computeDeficit(3, 4, 1, 2, 0, 4, 6, 0));
        // untested and in-progress reduce the batch
        assertEquals(6 - 3 - 1 - 1 + 2, TunnelPool.computeDeficit(3, 4, 1, 2, 1, 4, 6, 1));
        // healthy full: can go negative without Math.max on this branch — pin
        // the formula the caller uses (positive when there is a real gap)
        assertEquals(6 - 6 + 2, TunnelPool.computeDeficit(6, 6, 0, 2, 0, 4, 6, 0));
    }

    @Test
    public void testDeficitFailingBoostOnlyWhenHealthyPresent() {
        // empty pool must not double-count failing slots via the boost
        assertEquals(6, TunnelPool.computeDeficit(0, 0, 0, 4, 0, 4, 6, 0));
        // healthy present: failing tunnels get replacement slots staged
        assertTrue(TunnelPool.computeDeficit(2, 3, 1, 4, 0, 4, 6, 0) > 6 - 2);
    }

    // ---------- staged replacement at the soft degraded-bar crossing ----------

    @Test
    public void testDeficitStagesOneWhenFullPoolIsAllSoftDegraded() {
        // healthy=0, safeActive=target, nothing pending: the shortfall math
        // says "full" while no tunnel can be trusted with data — stage exactly
        // one replacement instead of idling until the removal bar
        assertEquals(1, TunnelPool.computeDeficit(0, 4, 4, 0, 0, 4, 4, 0));
    }

    @Test
    public void testDeficitStagesOnlyOnceWhileBuildInFlight() {
        assertEquals(0, TunnelPool.computeDeficit(0, 4, 4, 0, 0, 4, 4, 1));
    }

    @Test
    public void testDeficitNoStageWhileUntestedPending() {
        // the untested tunnel will become healthy capacity on its own
        assertEquals(0, TunnelPool.computeDeficit(0, 4, 4, 0, 1, 4, 4, 0));
    }

    @Test
    public void testDeficitNoStageAboveTargetOrAtCapacity() {
        // surplus beyond target+2 would be dropped at addTunnel's cap
        assertEquals(0, TunnelPool.computeDeficit(0, 6, 6, 0, 0, 4, 4, 0));
        // above target but also above effectiveTarget: not "full", surplus
        assertEquals(0, TunnelPool.computeDeficit(0, 5, 5, 0, 0, 4, 4, 0));
        // at effectiveTarget == target+2 (boosted): at the addTunnel cap
        assertEquals(0, TunnelPool.computeDeficit(0, 6, 6, 0, 0, 4, 6, 0));
    }

    @Test
    public void testDeficitNoStageWhenHealthyCapacityRemains() {
        // healthy path unchanged: builds the gap to the effective target
        assertEquals(6 - 1, TunnelPool.computeDeficit(1, 4, 3, 0, 0, 4, 6, 0));
    }

    // ---------- shouldBypassEnsureThrottle ----------

    @Test
    public void testHardFailureBypassesEnsureThrottle() {
        assertTrue(TunnelPool.shouldBypassEnsureThrottle(false, 1, false));
        assertTrue(TunnelPool.shouldBypassEnsureThrottle(false, 4, false));
    }

    @Test
    public void testSoftRemovalBypassesEnsureThrottle() {
        assertTrue(TunnelPool.shouldBypassEnsureThrottle(true, 11, true));
    }

    @Test
    public void testSoftDegradedCrossingBypassesEnsureThrottle() {
        assertTrue(TunnelPool.shouldBypassEnsureThrottle(true,
                TunnelPool.SOFT_DEGRADED_FOR_ENSURE, false));
    }

    @Test
    public void testRoutineSoftTimeoutStaysThrottled() {
        // below the bar, above the bar without a removal, and post-crossing
        // repeats must not re-run the ensure body per status-3
        assertFalse(TunnelPool.shouldBypassEnsureThrottle(true, 1, false));
        assertFalse(TunnelPool.shouldBypassEnsureThrottle(true, 4, false));
        assertFalse(TunnelPool.shouldBypassEnsureThrottle(true, 6, false));
        assertFalse(TunnelPool.shouldBypassEnsureThrottle(true, 9, false));
    }

    // ---------- hasValidTunnelsBlockingEmergency ----------

    @Test
    public void testEmergencyDeferredWhileValidTunnelsRemain() {
        assertTrue(TunnelPool.hasValidTunnelsBlockingEmergency(1));
        assertTrue(TunnelPool.hasValidTunnelsBlockingEmergency(6));
        assertFalse(TunnelPool.hasValidTunnelsBlockingEmergency(0));
    }

    // ---------- shouldDeferRemovalForRebuild ----------

    @Test
    public void testDeferRemovalWhenThinAndNothingBuilding() {
        assertTrue(TunnelPool.shouldDeferRemovalForRebuild(0, 0));
        assertTrue(TunnelPool.shouldDeferRemovalForRebuild(1, 0));
        assertTrue(TunnelPool.shouldDeferRemovalForRebuild(2, 0));
    }

    @Test
    public void testImmediateRemovalWhenBuildsStaged() {
        assertFalse(TunnelPool.shouldDeferRemovalForRebuild(2, 1));
        assertFalse(TunnelPool.shouldDeferRemovalForRebuild(1, 2));
    }

    @Test
    public void testImmediateRemovalWhenPoolStillHasCapacity() {
        assertFalse(TunnelPool.shouldDeferRemovalForRebuild(3, 0));
        assertFalse(TunnelPool.shouldDeferRemovalForRebuild(6, 0));
    }

    // ---------- SOFT_DEGRADED_FOR_ENSURE ----------

    @Test
    public void testSoftDegradedBarRaisedAboveOneThirdOfPool() {
        // Raised from 3 so transient congestion does not mark half the pool
        // degraded and trip collapse-sized rebuilds every few seconds.
        assertEquals(5, TunnelPool.SOFT_DEGRADED_FOR_ENSURE);
    }
}
