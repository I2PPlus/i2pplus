package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the emergency-cooldown decision
 * ({@link TunnelPool#isEmergencyCooldownActive(long, long, long)}).
 *
 * <p>The standard cooldown (30s) applies when the pool still has usable
 * tunnels or a build in progress; during total collapse — zero usable tunnels
 * AND zero builds in progress — the shorter 5s cooldown applies so recovery
 * isn't stranded behind a stale 30s wait.
 *
 * @since 0.9.72
 */
public class TunnelPoolEmergencyCooldownTest {

    private static final long STD = 30_000L;
    private static final long CRASH = 5_000L;

    /**
     * Fresh emergency build: cooldown active under both normal and
     * collapsed-pool conditions.
     */
    @Test
    public void testFreshBuildUnderAllConditions() {
        assertTrue(TunnelPool.isEmergencyCooldownActive(0, 2, 0));
        assertTrue(TunnelPool.isEmergencyCooldownActive(0, 0, 1));
        assertTrue(TunnelPool.isEmergencyCooldownActive(0, 0, 0));
    }

    /**
     * Boundary: at exactly the cooldown value the window is over.
     */
    @Test
    public void testBoundaryExactCooldownExpired() {
        assertFalse(TunnelPool.isEmergencyCooldownActive(STD, 2, 0));
        assertFalse(TunnelPool.isEmergencyCooldownActive(CRASH, 0, 0));
    }

    /**
     * Standard pool (usable tunnels present): the full 30s cooldown applies
     * even when the in-progress count is zero — a non-collapsed pool can
     * afford the wait.
     */
    @Test
    public void testStandardCooldownWhenNotCollapsed() {
        // usable>0, inProgress=0 -> standard 30s window
        assertTrue(TunnelPool.isEmergencyCooldownActive(STD - 1, 2, 0));
        assertFalse(TunnelPool.isEmergencyCooldownActive(STD, 2, 0));
        // usable=0 but a build in progress -> still standard 30s, not collapse
        assertTrue(TunnelPool.isEmergencyCooldownActive(STD - 1, 0, 1));
        assertFalse(TunnelPool.isEmergencyCooldownActive(STD, 0, 1));
    }

    /**
     * Total collapse (usable=0, inProgress=0): the short 5s cooldown is
     * believed-and-bounded — recovery is not stranded, but repeated attempts
     * are still spaced out enough to prevent build-storming.
     */
    @Test
    public void testShortCooldownOnlyDuringTotalCollapse() {
        assertTrue(TunnelPool.isEmergencyCooldownActive(CRASH - 1, 0, 0));
        assertFalse(TunnelPool.isEmergencyCooldownActive(CRASH, 0, 0));
        // A mere 5ms of elapsed time during collapse must still be active —
        // the first emergency build hasn't had any chance to finish.
        assertTrue(TunnelPool.isEmergencyCooldownActive(5, 0, 0));
        // Non-collapsed must NOT be active after 5s+elapsed — 5s < standard 30s
        assertTrue(TunnelPool.isEmergencyCooldownActive(5_000, 1, 0));
    }

    /**
     * The short cooldown must NOT apply when only one condition holds —
     * usable==0 but inProgress>0 uses the standard 30s window so a build
     * already underway isn't interrupted.
     */
    @Test
    public void testCollapseRequiresBothZero() {
        assertTrue(TunnelPool.isEmergencyCooldownActive(STD - 1, 0, 1));
        assertFalse(TunnelPool.isEmergencyCooldownActive(STD, 0, 1));
        assertTrue(TunnelPool.isEmergencyCooldownActive(STD - 1, 1, 0));
        assertFalse(TunnelPool.isEmergencyCooldownActive(STD, 1, 0));
    }
}