package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the TestJob hard-limit rules: the job-queue-lag floor on the
 * limit ({@link TestJob#scaleHardLimit(int, long)}) — lag scales the limit
 * down so TestJobs don't starve critical router jobs, but never below half
 * the base, since quartering starved the tunnel test queue while UNTESTED
 * tunnels piled up unscheduled — and the boundary the reservation itself
 * uses ({@link TestJob#canClaimInstance(int, int)}).
 *
 * @since 0.9.71+
 */
public class TestJobHardLimitTest {

    private static final int BASE = 512;

    @Test
    public void testNoLagKeepsBase() {
        assertEquals(BASE, TestJob.scaleHardLimit(BASE, 0));
        assertEquals(BASE, TestJob.scaleHardLimit(BASE, 5_000));
        assertEquals(384, TestJob.scaleHardLimit(384, 4_999));
    }

    @Test
    public void testModerateLagHalves() {
        assertEquals(BASE / 2, TestJob.scaleHardLimit(BASE, 5_001));
        assertEquals(BASE / 2, TestJob.scaleHardLimit(BASE, 14_999));
    }

    @Test
    public void testExtremeLagFloorsAtHalfNotQuarter() {
        // the regression: lag > 15s used to return base/4 (128), starving
        // the test queue; it must floor at base/2
        assertEquals(BASE / 2, TestJob.scaleHardLimit(BASE, 15_001));
        assertEquals(BASE / 2, TestJob.scaleHardLimit(BASE, 600_000));
        assertTrue(TestJob.scaleHardLimit(BASE, 600_000) >= BASE / 2);
    }

    @Test
    public void testSmallBasesNeverCollapseToZero() {
        assertEquals(1, TestJob.scaleHardLimit(1, 60_000));
        assertEquals(1, TestJob.scaleHardLimit(2, 60_000));
        assertEquals(1, TestJob.scaleHardLimit(3, 60_000));
        assertEquals(2, TestJob.scaleHardLimit(5, 60_000));
    }

    @Test
    public void testNonPositiveBasePassesThrough() {
        assertEquals(0, TestJob.scaleHardLimit(0, 60_000));
        assertEquals(-1, TestJob.scaleHardLimit(-1, 60_000));
    }

    // ---------------- canClaimInstance ----------------
    // Reservation is the sole hard-limit authority, so the boundary must be
    // exactly "current < hardLimit": admitting at the limit would leave
    // hardLimit + 1 instances.

    @Test
    public void testBelowLimitClaims() {
        assertTrue(TestJob.canClaimInstance(0, 512));
        assertTrue(TestJob.canClaimInstance(511, 512));
    }

    @Test
    public void testAtLimitRejected() {
        assertFalse(TestJob.canClaimInstance(512, 512));
        assertFalse(TestJob.canClaimInstance(513, 512));
    }

    @Test
    public void testSingleSlotLimit() {
        assertTrue(TestJob.canClaimInstance(0, 1));
        assertFalse(TestJob.canClaimInstance(1, 1));
    }

    @Test
    public void testZeroLimitNeverClaims() {
        assertFalse(TestJob.canClaimInstance(0, 0));
    }
}
