package net.i2p.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests the worker-count decision and its bounds for the shared timer.
 *
 * <p>The worker count was previously a constant two, which is enough only while
 * total event volume stays below what two threads can service. A live router ran
 * at a 16s median / 30s p90 execution delay across seven event types - ACKTimer,
 * KillTimer, BodyWatchdog, DelayedCloser, Cleaner, RemoveExpired,
 * BatchedSearchTimeoutProcessor - which is volume saturation rather than the
 * two-blocked-events wedge the saturation watchdog exists to catch. That watchdog
 * samples occupancy and queue depth at an instant, so it is blind to a backlog
 * whose events merely run back-to-back.
 *
 * <p>The decision is separated from the mechanism so the growth and release
 * thresholds can be pinned, including the hysteresis that keeps the count from
 * oscillating near a threshold.
 *
 * @since 0.9.71+
 */
public class SimpleTimer2ScalingTest {

    private static final int MAX = 16;

    /** A clean timer releases a worker, one step at a time. */
    @Test
    public void testCleanBacklogReleasesAWorker() {
        assertEquals(3, SimpleTimer2.desiredThreads(0, 4, MAX, 5_000, 1_000));
        assertEquals(3, SimpleTimer2.desiredThreads(1_000, 4, MAX, 5_000, 1_000));
    }

    /** A backlogged timer gains a worker. */
    @Test
    public void testBacklogGainsAWorker() {
        assertEquals(5, SimpleTimer2.desiredThreads(5_000, 4, MAX, 5_000, 1_000));
        assertEquals(5, SimpleTimer2.desiredThreads(30_000, 4, MAX, 5_000, 1_000));
    }

    /**
     * Between the two thresholds the count holds. This is the hysteresis band: with
     * grow and release thresholds close together the count would oscillate for as
     * long as the load sat between them, reallocating threads each cycle.
     */
    @Test
    public void testBetweenThresholdsHolds() {
        assertEquals(4, SimpleTimer2.desiredThreads(1_001, 4, MAX, 5_000, 1_000));
        assertEquals(4, SimpleTimer2.desiredThreads(4_999, 4, MAX, 5_000, 1_000));
    }

    /** Never below the floor: one worker starves on a single blocked event. */
    @Test
    public void testNeverBelowFloor() {
        assertEquals(2, SimpleTimer2.desiredThreads(0, 2, MAX, 5_000, 1_000));
        assertEquals(2, SimpleTimer2.desiredThreads(0, 3, MAX, 5_000, 1_000));
    }

    /** Never above the ceiling, however bad the backlog. */
    @Test
    public void testNeverAboveCeiling() {
        assertEquals(16, SimpleTimer2.desiredThreads(60_000, 16, 16, 5_000, 1_000));
        assertEquals(4, SimpleTimer2.desiredThreads(60_000, 4, 4, 5_000, 1_000));
    }

    /**
     * A non-positive ceiling must not be read as "no ceiling" and leave the count
     * unbounded: it falls back to the hard ceiling, so a caller passing a bad value
     * gets a bound rather than none.
     */
    @Test
    public void testNonPositiveCeilingFallsBackToTheHardCeiling() {
        // past the ceiling and still growing: held at the ceiling, not past it
        assertEquals(16, SimpleTimer2.desiredThreads(60_000, 16, 0, 5_000, 1_000));
        // a value below it still grows normally under the fallback bound
        assertEquals(4, SimpleTimer2.desiredThreads(60_000, 3, 0, 5_000, 1_000));
    }

    /**
     * The exact grow boundary belongs to growth, and the exact release boundary to
     * release, so a reading sitting precisely on either does the expected thing.
     */
    @Test
    public void testBoundariesBelongToTheirSide() {
        assertEquals(5, SimpleTimer2.desiredThreads(5_000, 4, MAX, 5_000, 1_000));
        assertEquals(3, SimpleTimer2.desiredThreads(1_000, 4, MAX, 5_000, 1_000));
    }

    /**
     * Growth is one step per cycle. A backlog deep enough to justify several more
     * workers must not add them all at once, because a backlog that clears on its
     * own would leave the excess allocated.
     */
    @Test
    public void testGrowthIsOneStepAtATime() {
        int threads = 2;
        for (int i = 0; i < 5; i++) {
            threads = SimpleTimer2.desiredThreads(30_000, threads, MAX, 5_000, 1_000);
        }
        assertEquals(7, threads);
    }

    /**
     * And release is likewise one step, so a timer that has gone quiet returns to
     * the floor gradually rather than collapsing.
     */
    @Test
    public void testReleaseIsOneStepAtATime() {
        int threads = 8;
        for (int i = 0; i < 4; i++) {
            threads = SimpleTimer2.desiredThreads(0, threads, MAX, 5_000, 1_000);
        }
        assertEquals(4, threads);
    }
}
