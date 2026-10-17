package net.i2p.i2ptunnel;

import junit.framework.TestCase;

/**
 * Tests the pure decision functions that govern runner-pool growth,
 * elevation, and decay.
 *
 * <p>These are the rules the Tuner relies on to size per-tunnel runner
 * pools: when to elevate past the ceiling, and when to release a stale
 * elevation. The logic is extracted as static functions so it can be
 * pinned without a live RouterContext.
 *
 * @since 0.9.71+
 */
public class RunnerPoolSizingTest extends TestCase {

    // ----- shouldElevateRunner -----

    /** Below the burst threshold: never elevate. */
    public void testBelowBurstThresholdDoesNotElevate() {
        assertFalse(TunnelControllerGroup.shouldElevateRunner(31, 64, 64));
        assertFalse(TunnelControllerGroup.shouldElevateRunner(0, 64, 64));
    }

    /** At the burst threshold with room to grow: elevate. */
    public void testAtBurstThresholdWithRoomElevates() {
        assertTrue(TunnelControllerGroup.shouldElevateRunner(32, 64, 64));
    }

    /** At the burst threshold but below the ceiling: grow within the ceiling, not past it. */
    public void testBelowCeilingDoesNotElevate() {
        assertFalse(TunnelControllerGroup.shouldElevateRunner(32, 33, 64));
        assertFalse(TunnelControllerGroup.shouldElevateRunner(63, 63, 64));
    }

    /** At the burst threshold with threads at or past the ceiling: elevate. */
    public void testAtBurstThresholdAtCeilingElevates() {
        assertTrue(TunnelControllerGroup.shouldElevateRunner(32, 64, 64));
        assertTrue(TunnelControllerGroup.shouldElevateRunner(33, 65, 64));
    }

    /** Ceiling below floor: floor wins, elevation requires reaching floor. */
    public void testCeilingBelowFloorUsesFloorAsMinimum() {
        // ceiling=2, floor=4 → effective ceiling = 4
        assertFalse(TunnelControllerGroup.shouldElevateRunner(32, 3, 2));
        assertTrue(TunnelControllerGroup.shouldElevateRunner(32, 4, 2));
    }

    // ----- shouldReleaseElevation -----

    /** Never elevated: nothing to release. */
    public void testNeverElevatedDoesNotRelease() {
        assertFalse(TunnelControllerGroup.shouldReleaseElevation(100, 50, 0));
    }

    /** No idle baseline recorded: nothing to release. */
    public void testNoIdleBaselineDoesNotRelease() {
        assertFalse(TunnelControllerGroup.shouldReleaseElevation(100, 0, 64));
    }

    /** Completed count went backwards (pool reset): no release. */
    public void testCompletedWentBackwardsDoesNotRelease() {
        assertFalse(TunnelControllerGroup.shouldReleaseElevation(50, 100, 64));
    }

    /** Work done since idle is below the elevation: hold. */
    public void testBelowElevationDoesNotRelease() {
        assertFalse(TunnelControllerGroup.shouldReleaseElevation(100, 50, 64));
    }

    /** Work done since idle reaches the elevation: release. */
    public void testAtElevationReleases() {
        assertTrue(TunnelControllerGroup.shouldReleaseElevation(114, 50, 64));
    }

    /** Work done since idle exceeds the elevation: release. */
    public void testAboveElevationReleases() {
        assertTrue(TunnelControllerGroup.shouldReleaseElevation(200, 50, 64));
    }

    /** Boundary: exactly at the elevation threshold. */
    public void testExactlyAtElevationBoundary() {
        // completed - idleSince == elevatedMax → release
        assertTrue(TunnelControllerGroup.shouldReleaseElevation(164, 100, 64));
    }

    /** Boundary: one below the elevation threshold. */
    public void testOneBelowElevationBoundary() {
        assertFalse(TunnelControllerGroup.shouldReleaseElevation(163, 100, 64));
    }
}
