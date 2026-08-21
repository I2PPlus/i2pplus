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

    @Test
    public void testBurstThresholdFloor() {
        // low-limit peers keep the configured floor
        assertEquals(10, RequestThrottler.burstThreshold(10, 50));
        assertEquals(10, RequestThrottler.burstThreshold(10, 100));
    }

    @Test
    public void testBurstThresholdScalesWithLimit() {
        // high-limit peers get a raised threshold: one tenth of their limit
        assertEquals(30, RequestThrottler.burstThreshold(10, 300));
        assertEquals(25, RequestThrottler.burstThreshold(10, 250));
        // and a higher configured floor wins when larger
        assertEquals(40, RequestThrottler.burstThreshold(40, 300));
    }

    @Test
    public void testEarlyBurstOffensesThrottleOnly() {
        assertEquals(0, RequestThrottler.burstBanDurationMs(1));
        assertEquals(0, RequestThrottler.burstBanDurationMs(2));
    }

    @Test
    public void testBurstBanLadder() {
        assertEquals(5 * 60 * 1000L, RequestThrottler.burstBanDurationMs(3));
        assertEquals(10 * 60 * 1000L, RequestThrottler.burstBanDurationMs(4));
        assertEquals(15 * 60 * 1000L, RequestThrottler.burstBanDurationMs(5));
    }

    @Test
    public void testBurstBanCappedAtThirtyMinutes() {
        assertEquals(30 * 60 * 1000L, RequestThrottler.burstBanDurationMs(9));
        assertEquals(30 * 60 * 1000L, RequestThrottler.burstBanDurationMs(100));
    }
}
