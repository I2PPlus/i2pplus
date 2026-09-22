package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for ensure/deficit throttle decisions
 * ({@link TunnelPool#isEnsureThrottled(long, long, int)} and
 * {@link TunnelPool#isDeficitThrottled(long, long, int, int)}).
 *
 * <p>Policy: long gates when the pool is healthy; short floors when
 * collapsed (usable/safe &lt;= 1) so recovery is prompt without letting
 * fast-failing builds hammer selectSingleHop on every completion.
 *
 * @since 0.9.71+
 */
public class TunnelPoolThrottleTest {

    private static final long ENSURE_HEALTHY_MS = 15_000L;
    private static final long ENSURE_COLLAPSED_MS = 2_000L;
    private static final long DEFICIT_HEALTHY_MS = 30_000L;
    private static final long DEFICIT_COLLAPSED_MS = 5_000L;

    // --- isEnsureThrottled ---

    @Test
    public void testEnsureNeverThrottledBeforeFirstRun() {
        assertFalse(TunnelPool.isEnsureThrottled(1_000_000L, 0, 5));
        assertFalse(TunnelPool.isEnsureThrottled(1_000_000L, 0, 0));
        assertFalse(TunnelPool.isEnsureThrottled(1_000_000L, 0, 1));
    }

    @Test
    public void testEnsureHealthyPoolUsesLongGate() {
        long last = 1_000_000L;
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_HEALTHY_MS - 1, last, 5));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_HEALTHY_MS, last, 5));
        // usable > 1 uses the long gate even when partially degraded
        assertTrue(TunnelPool.isEnsureThrottled(last + 5_000L, last, 2));
    }

    @Test
    public void testEnsureCollapsedPoolUsesShortFloor() {
        long last = 1_000_000L;
        // usable <= 1: short floor, not the 15s gate
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS - 1, last, 0));
        assertTrue(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS - 1, last, 1));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS, last, 0));
        assertFalse(TunnelPool.isEnsureThrottled(last + ENSURE_COLLAPSED_MS, last, 1));
        // 3s is past the collapsed floor but would still be throttled if healthy
        assertFalse(TunnelPool.isEnsureThrottled(last + 3_000L, last, 0));
        assertTrue(TunnelPool.isEnsureThrottled(last + 3_000L, last, 5));
    }

    // --- isDeficitThrottled ---

    @Test
    public void testDeficitNeverThrottledBeforeFirstBuild() {
        assertFalse(TunnelPool.isDeficitThrottled(1_000_000L, 0, 5, 0));
        assertFalse(TunnelPool.isDeficitThrottled(1_000_000L, 0, 0, 0));
    }

    @Test
    public void testDeficitHealthyPoolUses30sGate() {
        long last = 1_000_000L;
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_HEALTHY_MS - 1, last, 5, 0));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_HEALTHY_MS, last, 5, 0));
        // safe > 1 with builds in flight still uses the long gate
        assertTrue(TunnelPool.isDeficitThrottled(last + 10_000L, last, 5, 2));
    }

    @Test
    public void testDeficitCollapsedUsesShortCooldownNotFullBypass() {
        long last = 1_000_000L;
        // collapsed (safe<=1, inProgress==0): 5s cooldown — NOT a full bypass
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 0, 0));
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS - 1, last, 1, 0));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 0, 0));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_COLLAPSED_MS, last, 1, 0));
        // 6s past collapsed build is allowed; same elapsed under healthy is not
        assertFalse(TunnelPool.isDeficitThrottled(last + 6_000L, last, 0, 0));
        assertTrue(TunnelPool.isDeficitThrottled(last + 6_000L, last, 5, 0));
    }

    @Test
    public void testDeficitCollapsedButBuildsInFlightUsesLongGate() {
        long last = 1_000_000L;
        // safe<=1 but inProgress>0: not "collapsed" for throttle purposes
        assertTrue(TunnelPool.isDeficitThrottled(last + 10_000L, last, 0, 1));
        assertTrue(TunnelPool.isDeficitThrottled(last + DEFICIT_HEALTHY_MS - 1, last, 1, 2));
        assertFalse(TunnelPool.isDeficitThrottled(last + DEFICIT_HEALTHY_MS, last, 0, 1));
    }

    @Test
    public void testDeficitFirstAttemptAfterLongSilenceAlwaysAllowed() {
        // Simulates recovery after 50m collapse: last build long ago
        long now = 50L * 60_000L;
        long last = 1_000L;
        assertFalse(TunnelPool.isDeficitThrottled(now, last, 0, 0));
        assertFalse(TunnelPool.isDeficitThrottled(now, last, 1, 0));
    }
}
