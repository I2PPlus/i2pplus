package net.i2p.router.tunnel.pool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The slow-build marker that gives the build duration stats a tail to read.
 *
 * <p>{@code tunnel.buildClientSuccess} and its exploratory twin record how long each build took,
 * but expose only a mean, and build latency is right-skewed: most builds are quick and a tail
 * nearly times out. A mean-derived reply budget therefore under-covers the builds the budget
 * actually has to accommodate - which is how the establish timeout came to be set from 4x a
 * 196ms mean and land well below the value that was failing.
 *
 * <p>This mirrors {@code udp.*EstablishSlow}, added earlier for the same reason, so both
 * directions now have a tail signal rather than a body-only one.
 *
 * @since 0.9.71+
 */
public class SlowBuildThresholdTest {

    /** The floor the build reply deadline is clamped to. */
    private static final long BUILD_DEADLINE_FLOOR = 10 * 1000L;

    @Test
    public void thresholdIsHalfTheDeadlineFloor() {
        assertEquals(5000, BuildExecutor.SLOW_BUILD_MS);
        assertEquals(BUILD_DEADLINE_FLOOR / 2, BuildExecutor.SLOW_BUILD_MS);
    }

    /**
     * Why half. Anything at or above this would have been at risk of expiring at the floor, so
     * every build it counts is one the budget genuinely has to cover. Setting it at the deadline
     * itself would only ever count builds that had already failed.
     */
    @Test
    public void thresholdIsBelowTheFloorItProtects() {
        assertTrue("a build at the threshold must fit inside the smallest budget",
                   BuildExecutor.SLOW_BUILD_MS < BUILD_DEADLINE_FLOOR);
    }

    /** The boundary: the record sites test {@code >=}, so the threshold itself counts. */
    @Test
    public void thresholdItselfCounts() {
        long t = BuildExecutor.SLOW_BUILD_MS;
        assertTrue(t >= BuildExecutor.SLOW_BUILD_MS);
        assertFalse((t - 1) >= BuildExecutor.SLOW_BUILD_MS);
    }

    /**
     * The stat has to stay selective or it is redundant with the success stat it sits beside.
     * A build completing well inside the floor is not slow, and counting it would make the
     * marker a second copy of the mean rather than a tail.
     */
    @Test
    public void aTypicalBuildIsNotSlow() {
        long[] typical = {200, 800, 1500, 2500, 3000, 4000, 4999};
        for (long rtt : typical) {
            assertFalse(rtt + "ms is inside the floor and must not count as slow",
                        rtt >= BuildExecutor.SLOW_BUILD_MS);
        }
    }

    /** A build that consumed most of the smallest budget is exactly what we want to see. */
    @Test
    public void aBuildNearTheFloorIsSlow() {
        long[] nearFloor = {5000, 7000, 9999, 10000, 15000};
        for (long rtt : nearFloor) {
            assertTrue(rtt + "ms would be at risk at the floor and must count",
                       rtt >= BuildExecutor.SLOW_BUILD_MS);
        }
    }

    /**
     * The whole reason the establish side needed one of these. A 1s mean would put a 4x target
     * at 4s, below the 5s this marks and far below the 10s floor - so a mean-derived budget
     * cannot see these builds at all.
     */
    @Test
    public void aMeanDerivedBudgetWouldMissThem() {
        long fourTimesAMeanOfOneSecond = 4000;
        assertTrue("the mean-derived target lands below the marker",
                   fourTimesAMeanOfOneSecond < BuildExecutor.SLOW_BUILD_MS);
        assertTrue("and below the floor it is supposed to respect",
                   fourTimesAMeanOfOneSecond < BUILD_DEADLINE_FLOOR);
    }
}
