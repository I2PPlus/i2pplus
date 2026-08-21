package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the idle-cull pressure gate: culling only runs when transit usage is
 * at or above 80% of configured capacity; unknown capacity never culls.
 *
 * @since 0.9.71+
 */
public class IdleTunnelMonitorCapacityTest {

    @Test
    public void testBelowThresholdDoesNotCull() {
        assertFalse(IdleTunnelMonitor.nearCapacity(0, 10000));
        assertFalse(IdleTunnelMonitor.nearCapacity(5000, 10000));
        assertFalse(IdleTunnelMonitor.nearCapacity(7999, 10000));
    }

    @Test
    public void testAtOrAboveThresholdCulls() {
        assertTrue(IdleTunnelMonitor.nearCapacity(8000, 10000));
        assertTrue(IdleTunnelMonitor.nearCapacity(9500, 10000));
        assertTrue(IdleTunnelMonitor.nearCapacity(10000, 10000));
    }

    @Test
    public void testEightyPercentBoundary() {
        // exact boundary: usage == fraction * max
        assertFalse(IdleTunnelMonitor.nearCapacity(79, 100));
        assertTrue(IdleTunnelMonitor.nearCapacity(80, 100));
    }

    @Test
    public void testSmallCapacities() {
        // small pools: 4 of 5 = exactly 80%
        assertTrue(IdleTunnelMonitor.nearCapacity(4, 5));
        assertFalse(IdleTunnelMonitor.nearCapacity(3, 5));
    }

    @Test
    public void testUnknownCapacityNeverCulls() {
        assertFalse(IdleTunnelMonitor.nearCapacity(12000, 0));
        assertFalse(IdleTunnelMonitor.nearCapacity(12000, -1));
    }
}
