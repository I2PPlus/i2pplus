package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the failure-scaled pre-build window
 * ({@link TunnelPool#computePreBuildWindowMs(double)},
 * {@link TunnelPool#timeoutRate(long, long)}, and
 * {@link TunnelPool#rotateWindowStart(long, long, long)}): replacement builds
 * start earlier when the pool's builds are timing out, capped so the pool is
 * not kept in perpetual build mode.
 *
 * @since 0.9.71+
 */
public class PreBuildWindowTest {

    private static final long BASE = TunnelPool.PRE_BUILD_WINDOW_MS;
    private static final long MAX = TunnelPool.MAX_PRE_BUILD_WINDOW_MS;

    // --- computePreBuildWindowMs ---

    @Test
    public void testBaseWindowAtZeroRate() {
        assertEquals(BASE, TunnelPool.computePreBuildWindowMs(0.0));
    }

    @Test
    public void testScalesLinearlyWithRate() {
        assertEquals(BASE + BASE / 2, TunnelPool.computePreBuildWindowMs(0.5));
        assertEquals(2 * BASE, TunnelPool.computePreBuildWindowMs(1.0));
    }

    @Test
    public void testCapsAtMaxWindow() {
        assertEquals(MAX, TunnelPool.computePreBuildWindowMs(2.0));
        assertEquals(MAX, TunnelPool.computePreBuildWindowMs(Double.MAX_VALUE));
        // 3min * 2 = 6min exactly hits the cap
        assertTrue(TunnelPool.computePreBuildWindowMs(1.0) <= MAX);
    }

    @Test
    public void testMissingDataKeepsBaseWindow() {
        assertEquals(BASE, TunnelPool.computePreBuildWindowMs(Double.NaN));
        assertEquals(BASE, TunnelPool.computePreBuildWindowMs(-1.0));
    }

    // --- timeoutRate ---

    @Test
    public void testRateWithNoAttemptsIsZero() {
        assertEquals(0.0, TunnelPool.timeoutRate(0, 0), 0.0001);
        assertEquals(0.0, TunnelPool.timeoutRate(0, 5), 0.0001);
        assertEquals(0.0, TunnelPool.timeoutRate(-1, -1), 0.0001);
    }

    @Test
    public void testRateWithNoTimeoutsIsZero() {
        assertEquals(0.0, TunnelPool.timeoutRate(10, 0), 0.0001);
    }

    @Test
    public void testRateIsFractionOfAttempts() {
        assertEquals(0.25, TunnelPool.timeoutRate(8, 2), 0.0001);
        assertEquals(1.0, TunnelPool.timeoutRate(4, 4), 0.0001);
    }

    @Test
    public void testRateClampedWhenTimeoutsExceedAttempts() {
        assertEquals(1.0, TunnelPool.timeoutRate(3, 7), 0.0001);
        // Nonsense inputs (negative timeouts) count as no timeouts
        assertEquals(0.0, TunnelPool.timeoutRate(1, -2), 0.0001);
    }

    // --- rotateWindowStart ---

    @Test
    public void testFirstObservationStartsWindow() {
        assertEquals(1_000L, TunnelPool.rotateWindowStart(0, 1_000L, 60_000L));
        assertEquals(1_000L, TunnelPool.rotateWindowStart(-5, 1_000L, 60_000L));
    }

    @Test
    public void testWindowKeepsStartWhileFresh() {
        assertEquals(1_000L, TunnelPool.rotateWindowStart(1_000L, 60_999L, 60_000L));
    }

    @Test
    public void testWindowRotatesWhenExpired() {
        assertEquals(61_000L, TunnelPool.rotateWindowStart(1_000L, 61_000L, 60_000L));
        assertEquals(120_000L, TunnelPool.rotateWindowStart(1_000L, 120_000L, 60_000L));
    }

    // --- end-to-end: a full-timeout pool gets the maximum lead time ---

    @Test
    public void testFullTimeoutPoolGetsDoubleWindow() {
        double rate = TunnelPool.timeoutRate(4, 4);
        assertEquals(2 * BASE, TunnelPool.computePreBuildWindowMs(rate));
    }
}
