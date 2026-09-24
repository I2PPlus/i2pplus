package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the stale-UNTESTED last-chance decision
 * ({@link TunnelPool#isLastChanceTestable(long, long, long)}): a tunnel
 * expiring within the pre-build window is kept for one final test only
 * when enough life remains for the round trip to complete before expiry.
 *
 * @since 0.9.71+
 */
public class TunnelPoolLastChanceTest {

    private static final long NOW = 1_000_000L;
    private static final long MIN_LIFE = TunnelPool.LAST_CHANCE_MIN_LIFE_MS;

    @Test
    public void testPlentyOfLifeTestable() {
        assertTrue(TunnelPool.isLastChanceTestable(NOW + 5 * 60_000, NOW, MIN_LIFE));
    }

    @Test
    public void testAtMinimumLifeTestable() {
        assertTrue(TunnelPool.isLastChanceTestable(NOW + MIN_LIFE, NOW, MIN_LIFE));
    }

    @Test
    public void testJustBelowMinimumLifeNotTestable() {
        assertFalse(TunnelPool.isLastChanceTestable(NOW + MIN_LIFE - 1, NOW, MIN_LIFE));
    }

    @Test
    public void testExpiredNotTestable() {
        assertFalse(TunnelPool.isLastChanceTestable(NOW - 1, NOW, MIN_LIFE));
        assertFalse(TunnelPool.isLastChanceTestable(NOW, NOW, MIN_LIFE));
    }

    @Test
    public void testTypicalStaleWindowIsTestable() {
        // stale-UNTESTED means expiring within the 3-6 min pre-build window;
        // anywhere in that band with >= 60s left gets its one shot
        long preBuild = 3 * 60_000;
        assertTrue(TunnelPool.isLastChanceTestable(NOW + preBuild - 1, NOW, MIN_LIFE));
        assertTrue(TunnelPool.isLastChanceTestable(NOW + MIN_LIFE, NOW, MIN_LIFE));
    }

    @Test
    public void testConstantsConsistent() {
        // the min life must stay below the smallest pre-build window,
        // otherwise last-chance pruning could never fire
        assertTrue(TunnelPool.LAST_CHANCE_MIN_LIFE_MS < TunnelPool.PRE_BUILD_WINDOW_MS);
        assertTrue(TunnelPool.MAX_LAST_CHANCE_PER_SWEEP >= 1);
    }
}
