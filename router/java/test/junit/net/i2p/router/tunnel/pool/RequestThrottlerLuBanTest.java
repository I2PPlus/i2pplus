package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the load-scaled LU prev-hop enforcement probability: zero when idle,
 * linear to full enforcement at 50% load, clamped to 0.0-1.0.
 *
 * @since 0.9.71+
 */
public class RequestThrottlerLuBanTest {

    @Test
    public void testIdleMeansNoEnforcement() {
        assertEquals(0.0f, RequestThrottler.luEnforcementProbability(0.0f), 0.0001f);
    }

    @Test
    public void testLinearScaling() {
        assertEquals(0.25f, RequestThrottler.luEnforcementProbability(0.125f), 0.0001f);
        assertEquals(0.5f, RequestThrottler.luEnforcementProbability(0.25f), 0.0001f);
        assertEquals(0.75f, RequestThrottler.luEnforcementProbability(0.375f), 0.0001f);
    }

    @Test
    public void testFullEnforcementAtHalfLoad() {
        assertEquals(1.0f, RequestThrottler.luEnforcementProbability(0.5f), 0.0001f);
        assertEquals(1.0f, RequestThrottler.luEnforcementProbability(0.75f), 0.0001f);
        assertEquals(1.0f, RequestThrottler.luEnforcementProbability(1.0f), 0.0001f);
    }

    @Test
    public void testClampedToUnitRange() {
        assertEquals(0.0f, RequestThrottler.luEnforcementProbability(-1.0f), 0.0001f);
        assertEquals(1.0f, RequestThrottler.luEnforcementProbability(5.0f), 0.0001f);
    }
}
