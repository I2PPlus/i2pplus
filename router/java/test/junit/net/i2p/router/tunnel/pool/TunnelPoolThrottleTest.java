package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for ensure/deficit throttle decisions
 * ({@link TunnelPool#isEnsureThrottled(long, long, int, int, boolean)},
 * {@link TunnelPool#isDeficitThrottled(long, long, int, int, boolean)}, and the
 * healthySafe/safeActive split {@link TunnelPool#isDeficitThrottled(long, long, int, int, int, boolean)}).
 *
 * <p>Policy: long gates when the pool is healthy; intermediate gates while
 * the LeaseSet is incomplete or the pool is soft-degraded-dominated
 * (safeActive &gt; 0 but healthySafe == 0); short floors only when safeActive
 * is truly empty (or &lt;= 1 with nothing in flight) so recovery is prompt
 * without letting fast-failing builds hammer selectSingleHop on every
 * completion.  An empty pool uses the short floor even with builds in
 * flight — hung builds (17-30s timeouts) must not pin recovery on the 30s
 * healthy gate.  Soft-degraded tunnels pass tests but fail data-phase
 * sends, so a full-looking pool of them must not hold the 15s/30s healthy
 * gates, and must not thrash the 5s collapse rebuild either.
 *
 * @since 0.9.71+
 */
public class TunnelPoolThrottleTest {

    private static final long ENSURE_HEALTHY_MS = 15_000L;
    private static final long ENSURE_DEGRADED_MS = 5_000L;
    private static final long ENSURE_COLLAPSED_MS = 2_000L;
    private static final long DEFICIT_HEALTHY_MS = 30_000L;
    private static final long DEFICIT_DEGRADED_MS = 10_000L;
    private static final long DEFICIT_COLLAPSED_MS = 5_000L;

    // --- isEnsureThrottled (4-arg: healthy == usable) ---

    @Test
    public void testEnsureNeverThrottledBeforeFirstRun() {
        assertFalse(TunnelPool.isEnsureThrottled(1_000_000L, 0, 5, false));
        assertFalse(TunnelPool.isEnsureThrottled(1_000_000L, 0, 0, false));
        assertFalse(TunnelPool.isEnsureThrottled(1_000_000L, 0, 1, false));
        assertFalse(TunnelPool.isEnsureThrottled(1_000_000L, 0, 5, true));
        assertFalse(TunnelPool.isEnsureThrottled(1_000_000L, 0, 5, 5, false));
        assertFalse(TunnelPool.isEnsureThrottled(1_000_000L, 0, 5, 0, false));
    }

    @Test
    public void testEnsureHealthyPoolUsesLongGate() {
        long last = 1_000_000L;
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_HEALTHY_MS - 1, last, 5, false));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_HEALTHY_MS, last, 5, false));
        // usable > 1 uses the long gate even when partially degraded
        assertTrue(TunnelPool.isEnsureThrottled(last + 5_000L, last, 2, false));
        // 5-arg: healthy == usable uses the long gate
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_HEALTHY_MS - 1, last, 5, 5, false));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_HEALTHY_MS, last, 5, 5, false));
    }

    @Test
    public void testEnsureIncompleteLsUsesDegradedGate() {
        long last = 1_000_000L;
        // incomplete LS + usable > 1: 5s gate, not 15s
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_DEGRADED_MS - 1, last, 5, true));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_DEGRADED_MS, last, 5, true));
        // still within healthy window but past degraded: allowed
        assertFalse(TunnelPool.isEnsureThrottled(last + 6_000L, last, 5, true));
        assertTrue(TunnelPool.isEnsureThrottled(last + 6_000L, last, 5, false));
    }

    @Test
    public void testEnsureCollapsedPoolUsesShortFloor() {
        long last = 1_000_000L;
        // usable <= 1: short floor, not the 15s or 5s gate
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS - 1, last, 0, false));
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS - 1, last, 1, false));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS, last, 0, false));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS, last, 1, false));
        // collapse floor wins over incomplete-LS degraded gate
        assertFalse(TunnelPool.isEnsureThrottled(last + 3_000L, last, 0, true));
        assertTrue(TunnelPool.isEnsureThrottled(last + 3_000L, last, 5, false));
    }

    // --- isEnsureThrottled (5-arg: separate healthy count) ---

    @Test
    public void testEnsureSoftDegradedDominatedUsesShortFloor() {
        long last = 1_000_000L;
        // full pool of soft-degraded (usable=4, healthy=0): 2s floor, not 15s
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS - 1, last, 4, 0, false));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS, last, 4, 0, false));
        // healthy == 1 also uses the short floor
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS - 1, last, 4, 1, false));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS, last, 4, 1, false));
        // past the short floor but within the long gate: allowed
        assertFalse(TunnelPool.isEnsureThrottled(last + 3_000L, last, 4, 0, false));
    }

    @Test
    public void testEnsurePartialSoftDegradedUsesDegradedGate() {
        long last = 1_000_000L;
        // usable=4, healthy=2 (some soft-degraded): 5s gate, not 15s
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_DEGRADED_MS - 1, last, 4, 2, false));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_DEGRADED_MS, last, 4, 2, false));
        // past degraded but within healthy: allowed
        assertFalse(TunnelPool.isEnsureThrottled(last + 6_000L, last, 4, 2, false));
        assertTrue(TunnelPool.isEnsureThrottled(last + 6_000L, last, 4, 4, false));
        // incomplete LS also uses the degraded gate when healthy
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_DEGRADED_MS - 1, last, 4, 4, true));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_DEGRADED_MS, last, 4, 4, true));
    }

    // --- isDeficitThrottled ---

    @Test
    public void testDeficitNeverThrottledBeforeFirstBuild() {
        assertFalse(TunnelPool.isDeficitThrottled(1_000_000L, 0, 5, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(1_000_000L, 0, 0, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(1_000_000L, 0, 5, 0, true));
    }

    @Test
    public void testDeficitHealthyPoolUses30sGate() {
        long last = 1_000_000L;
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_HEALTHY_MS - 1, last, 5, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_HEALTHY_MS, last, 5, 0, false));
        // safe > 1 with builds in flight still uses the long gate
        assertTrue(TunnelPool.isDeficitThrottled(last + 10_000L, last, 5, 2, false));
    }

    @Test
    public void testDeficitIncompleteLsUsesDegradedGate() {
        long last = 1_000_000L;
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_DEGRADED_MS - 1, last, 5, 0, true));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_DEGRADED_MS, last, 5, 0, true));
        // past degraded but within healthy: allowed when incomplete
        assertFalse(TunnelPool.isDeficitThrottled(last + 11_000L, last, 5, 0, true));
        assertTrue(TunnelPool.isDeficitThrottled(last + 11_000L, last, 5, 0, false));
    }

    @Test
    public void testDeficitCollapsedUsesShortCooldownNotFullBypass() {
        long last = 1_000_000L;
        // collapsed (safe<=1, inProgress==0): 5s cooldown — NOT a full bypass
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 0, 0, false));
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 1, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 0, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 1, 0, false));
        // 6s past collapsed build is allowed; same elapsed under healthy is not
        assertFalse(TunnelPool.isDeficitThrottled(last + 6_000L, last, 0, 0, false));
        assertTrue(TunnelPool.isDeficitThrottled(last + 6_000L, last, 5, 0, false));
        // collapse floor wins over incomplete-LS degraded gate
        assertFalse(TunnelPool.isDeficitThrottled(last + 6_000L, last, 0, 0, true));
        // empty pool with builds in flight still uses the short cooldown
        assertFalse(TunnelPool.isDeficitThrottled(last + 6_000L, last, 0, 1, true));
        assertTrue(TunnelPool.isDeficitThrottled(last + 4_000L, last, 0, 2, false));
    }

    @Test
    public void testDeficitEmptyPoolUsesCollapseCooldownEvenWithBuildsInFlight() {
        long last = 1_000_000L;
        // Empty pool (healthy=0) with hung builds must not pin recovery on
        // the 30s healthy gate — 5s collapse cooldown applies.
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 0, 1, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 0, 1, false));
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 0, 2, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 0, 2, false));
        // 6s past empty-pool build is allowed; same elapsed under healthy is not
        assertFalse(TunnelPool.isDeficitThrottled(last + 6_000L, last, 0, 1, false));
        assertTrue(TunnelPool.isDeficitThrottled(last + 6_000L, last, 5, 0, false));
        // collapse floor wins over incomplete-LS degraded gate for empty pool
        assertFalse(TunnelPool.isDeficitThrottled(last + 6_000L, last, 0, 1, true));
    }

    @Test
    public void testDeficitOneHealthyWithBuildsInFlightUsesLongGate() {
        long last = 1_000_000L;
        // One healthy tunnel with builds in flight: not collapsed — 30s gate
        // so fast-failing builds cannot hammer selectSingleHop while capacity remains.
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_HEALTHY_MS - 1, last, 1, 2, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_HEALTHY_MS, last, 1, 2, false));
        // one healthy, nothing in flight: still collapsed (5s)
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 1, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 1, 0, false));
    }

    @Test
    public void testDeficitSoftDegradedHealthyCountUsesCollapseCooldown() {
        long last = 1_000_000L;
        // 5-arg compatibility overload (healthy == safeActive): a truly empty
        // pool uses the 5s collapse cooldown, not the 30s healthy gate — even
        // with builds in flight (common during a soft-fail rebuild storm).
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 0, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 0, 0, false));
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 0, 3, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 0, 3, false));
        // partial soft-degraded (healthy=2) still uses the long gate
        assertTrue(TunnelPool.isDeficitThrottled(last + 10_000L, last, 2, 0, false));
    }

    @Test
    public void testDeficitSoftDominatedPoolUsesDegradedNotCollapse() {
        long last = 1_000_000L;
        // 6-arg: full soft-degraded pool (healthy=0, safeActive=6) occupies
        // safe slots — 10s degraded gate, NOT the 5s collapse rebuild and
        // NOT the 30s healthy gate.
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_DEGRADED_MS - 1, last, 0, 6, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_DEGRADED_MS, last, 0, 6, 0, false));
        // past degraded but within healthy window: allowed
        assertFalse(TunnelPool.isDeficitThrottled(last + 11_000L, last, 0, 6, 0, false));
        assertTrue(TunnelPool.isDeficitThrottled(last + 11_000L, last, 6, 6, 0, false));
        // soft-dominated with builds in flight still uses the degraded gate
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_DEGRADED_MS - 1, last, 0, 6, 3, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_DEGRADED_MS, last, 0, 6, 3, false));
    }

    @Test
    public void testDeficitTrulyEmptyStillCollapsesWith6Arg() {
        long last = 1_000_000L;
        // 6-arg empty pool (healthy=0, safeActive=0): 5s collapse, not 10s/30s
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 0, 0, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 0, 0, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + 6_000L, last, 0, 0, 0, false));
        // one safe tunnel, nothing in flight: still collapsed
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 1, 1, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 1, 1, 0, false));
    }

    @Test
    public void testDeficitFirstAttemptAfterLongSilenceAlwaysAllowed() {
        // Simulates recovery after 50m collapse: last build long ago
        long now = 50L * 60_000L;
        long last = 1_000L;
        assertFalse(TunnelPool.isDeficitThrottled(now, last, 0, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(now, last, 1, 0, false));
        assertFalse(TunnelPool.isDeficitThrottled(now, last, 5, 0, true));
        assertFalse(TunnelPool.isDeficitThrottled(now, last, 0, 6, 0, false));
    }
}
