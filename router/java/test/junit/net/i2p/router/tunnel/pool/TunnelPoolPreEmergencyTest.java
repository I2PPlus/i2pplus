package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Pure decision tests for the pre-emergency fast-path in
 * {@link TunnelPool#ensureSufficientTunnels()}.
 *
 * <p>Policy: a pool below 50% of target is an emergency and must start
 * builds immediately, not through the normal throttle gate.  At or above
 * 50% the normal throttled path applies.  The threshold deliberately
 * equals the {@link TunnelPool#isMateriallyShort(int, int)} line, so the
 * pre-emergency branch owns the whole thin band and the separate
 * sub-50% fast-path that used to sit below it was removed; the
 * equivalence test below pins that invariant against drift.
 *
 * @since 0.9.71+
 */
public class TunnelPoolPreEmergencyTest {

    @Test
    public void testPreEmergencyFractionIsHalf() {
        assertEquals(0.5d, TunnelPool.PRE_EMERGENCY_FRACTION, 0.0001d);
    }

    private static boolean isPreEmergency(int target, int healthy) {
        // Mirrors the production guard, including the negative-health guard.
        return target > 0 && healthy >= 0 && healthy < target * TunnelPool.PRE_EMERGENCY_FRACTION;
    }

    @Test
    public void testBelowHalfIsPreEmergency() {
        // healthy=0, target=4: 0 < 2.0 -> pre-emergency
        assertTrue(isPreEmergency(4, 0));
        // healthy=1, target=4: 1 < 2.0 -> pre-emergency. This is the change:
        // at the old 0.25 fraction a 4-tunnel pool only burst at zero healthy.
        assertTrue(isPreEmergency(4, 1));
        // healthy=3, target=12: 3 < 6.0 -> pre-emergency (was normal path at 0.25)
        assertTrue(isPreEmergency(12, 3));
        // healthy=0, target=12: 0 < 6.0 -> pre-emergency
        assertTrue(isPreEmergency(12, 0));
    }

    @Test
    public void testAtHalfIsNotPreEmergency() {
        // healthy=6, target=12: 6 < 6.0 is false -> normal path
        assertFalse(isPreEmergency(12, 6));
        // healthy=2, target=4: 2 < 2.0 is false -> normal path
        assertFalse(isPreEmergency(4, 2));
        // odd target: healthy=2, target=3: 2 < 1.5 is false -> normal path
        assertFalse(isPreEmergency(3, 2));
    }

    @Test
    public void testAboveHalfIsNotPreEmergency() {
        // healthy=3, target=4: 3 < 2.0 is false -> normal path
        assertFalse(isPreEmergency(4, 3));
        // healthy=11, target=12: 11 < 6.0 is false -> normal path
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

    /**
     * The pre-emergency threshold must stay exactly on the isMateriallyShort
     * line: the removed sub-50% fast-path was unreachable only because the two
     * conditions agree for every target/healthy pair. If either moves, a band
     * of thin-pool states would fall to neither path's burst or the removed
     * path's warning, and this test must fail.
     */
    @Test
    public void testThresholdEqualsMateriallyShortLine() {
        for (int target = 1; target <= 16; target++) {
            for (int healthy = 0; healthy <= target; healthy++) {
                assertEquals("target=" + target + " healthy=" + healthy,
                             TunnelPool.isMateriallyShort(healthy, target),
                             isPreEmergency(target, healthy));
            }
        }
    }
}
