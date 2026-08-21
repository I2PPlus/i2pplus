package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the expiration stagger cap: lifetime plus stagger must stay under
 * the netdb's 15-minute future-lease rejection boundary, with a margin, and
 * never exceed 4 minutes.
 *
 * @since 0.9.71+
 */
public class TunnelPoolStaggerTest {

    private static final long MIN = 60 * 1000L;

    @Test
    public void testDefaultLifetime() {
        // 11-min lifetime -> 225s stagger (240s cap minus 15s margin)
        assertEquals(225 * 1000L, TunnelPool.maxExpirationStagger(11 * MIN));
        // 11 min + 225 s = 14:45, exactly 15s under the boundary
        assertEquals(15 * 1000L, (15 * MIN) - (11 * MIN) - TunnelPool.maxExpirationStagger(11 * MIN));
    }

    @Test
    public void testShortLifetimeHitsCeiling() {
        assertEquals(240 * 1000L, TunnelPool.maxExpirationStagger(5 * MIN));
    }

    @Test
    public void testLongLifetimeShrinksStagger() {
        // 14-min lifetime leaves only 45s of headroom
        assertEquals(45 * 1000L, TunnelPool.maxExpirationStagger(14 * MIN));
    }

    @Test
    public void testLifetimeAtBoundaryNoOps() {
        assertEquals(0, TunnelPool.maxExpirationStagger(15 * MIN));
    }

    @Test
    public void testLifetimePastBoundaryNoOps() {
        assertEquals(0, TunnelPool.maxExpirationStagger(20 * MIN));
    }
}
