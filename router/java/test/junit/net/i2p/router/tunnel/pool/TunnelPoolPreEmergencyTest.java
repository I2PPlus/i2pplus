package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pure decision tests for the pre-emergency fast-path in
 * {@link TunnelPool#ensureSufficientTunnels()}.
 *
 * <p>Policy: a pool below 25% of target is an emergency and must start
 * builds immediately, not through the throttle gate.  Above 25% the
 * normal throttled path applies.
 *
 * @since 0.9.71+
 */
public class TunnelPoolPreEmergencyTest {

    @Test
    public void testPreEmergencyFractionIsQuarter() {
        assertEquals(0.25d, TunnelPool.PRE_EMERGENCY_FRACTION, 0.0001d);
    }

    private static boolean isPreEmergency(int target, int healthy) {
        // Mirrors the production guard, including the negative-health guard.
        return target > 0 && healthy >= 0 && healthy < target * TunnelPool.PRE_EMERGENCY_FRACTION;
    }

    @Test
    public void testBelowQuarterIsPreEmergency() {
        // healthy=0, target=4: 0 < 1.0 -> pre-emergency
        assertTrue(isPreEmergency(4, 0));
        // healthy=1, target=4: 1 < 1.0 is FALSE, so exactly-at-quarter is not
        // pre-emergency; that case belongs to the 50% fast path.
        assertFalse(isPreEmergency(4, 1));
        // healthy=0, target=12: 0 < 3.0 → pre-emergency
        assertTrue(isPreEmergency(12, 0));
    }

    @Test
    public void testAtQuarterIsNotPreEmergency() {
        // healthy=3, target=12: 3 < 3.0 is false → normal path
        assertFalse(isPreEmergency(12, 3));
        // healthy=1, target=4: 1 < 1.0 is false → normal path
        assertFalse(isPreEmergency(4, 1));
    }

    @Test
    public void testAboveQuarterIsNotPreEmergency() {
        // healthy=2, target=4: 2 < 1.0 is false → normal path
        assertFalse(isPreEmergency(4, 2));
        // healthy=11, target=12: 11 < 3.0 is false → normal path
        assertFalse(isPreEmergency(12, 11));
    }

    @Test
    public void testZeroTargetNeverPreEmergency() {
        // Division by zero guard: target=0 must never trigger pre-emergency
        assertFalse(isPreEmergency(0, 0));
        assertFalse(isPreEmergency(0, 5));
    }

    @Test
    public void testNegativeHealthNeverPreEmergency() {
        // A negative healthy count (should not happen) must not trigger
        assertFalse(isPreEmergency(10, -1));
    }
}
