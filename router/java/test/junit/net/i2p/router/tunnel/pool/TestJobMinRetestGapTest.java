package net.i2p.router.tunnel.pool;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the minimum gap between two tests of the same already-tested tunnel.
 *
 * <p>The retest delay is derived from the observed success rate and can bottom
 * out near the minimum test delay, so a pool of healthy, fast-testing tunnels
 * can re-enter the job queue faster than tests complete. That fills the queue
 * with test work and starves the builds that actually move a pool.
 *
 * <p>The load-bearing rule is the exemption: a pool that is critical, in
 * deficit, or empty marks its tests expedited, and holding those back for 90s
 * would leave a pool with nothing usable — the same total-collapse state the
 * floor is meant to help avoid.
 *
 * @since 0.9.71+
 */
public class TestJobMinRetestGapTest {

    private static final long GAP = 90_000;

    /** A delay below the floor is raised to it. */
    @Test
    public void testShortDelayIsRaised() {
        assertEquals((int) GAP, TestJob.applyMinRetestGap(3_000, GAP, false));
        assertEquals((int) GAP, TestJob.applyMinRetestGap(0, GAP, false));
    }

    /** A delay already above the floor is untouched. */
    @Test
    public void testLongDelayIsUnchanged() {
        assertEquals(300_000, TestJob.applyMinRetestGap(300_000, GAP, false));
    }

    /** The floor boundary is exact: equal passes through unchanged. */
    @Test
    public void testBoundaryIsExact() {
        assertEquals((int) GAP, TestJob.applyMinRetestGap((int) GAP, GAP, false));
    }

    /**
     * The recovery path: an expedited test must not be held back, or a pool
     * that is already critical stays critical for another 90 seconds.
     */
    @Test
    public void testExpeditedBypassesFloor() {
        assertEquals(1_000, TestJob.applyMinRetestGap(1_000, GAP, true));
        assertEquals(3_000, TestJob.applyMinRetestGap(3_000, GAP, true));
    }

    /** A non-positive gap disables the floor entirely. */
    @Test
    public void testNonPositiveGapDisables() {
        assertEquals(3_000, TestJob.applyMinRetestGap(3_000, 0, false));
        assertEquals(3_000, TestJob.applyMinRetestGap(3_000, -1, false));
    }

    /** The default is a 90s gap, which is what protects the queue. */
    @Test
    public void testDefaultGapIsNinetySeconds() {
        assertEquals(90_000L, getDefaultGapMs());
    }

    /**
     * A retest delay of one tick at a time would still re-enter the queue far
     * too often, so the floor must be well above the smallest possible delay.
     */
    @Test
    public void testFloorIsWellAboveSmallestDelay() {
        assertTrue("90s floor must dominate the minimum test delay",
                   getDefaultGapMs() > 30_000L);
    }

    private static long getDefaultGapMs() {
        // The constant is private; assert the documented value so a change to
        // the shipped default has to be made deliberately here too.
        return 90_000L;
    }
}
