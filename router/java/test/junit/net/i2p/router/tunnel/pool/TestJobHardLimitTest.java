package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the TestJob hard-limit floor
 * ({@link TestJob#scaleHardLimit(int, long)}): job-queue lag scales the
 * limit down so TestJobs don't starve critical router jobs, but never below
 * half the base — quartering starved the tunnel test queue while UNTESTED
 * tunnels piled up unscheduled.
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
}
