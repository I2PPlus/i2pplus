package net.i2p.router.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the retry delay applied to a rate whose graph listener failed to start.
 *
 * <p>The fault these pin down is the one that took out every persistent graph at once: the
 * temporary file the RRD backends shared was owned by another user, so each of the 33 rates failed
 * to open at startup. A rate that fails lands in neither the listener list nor the rate map, so the
 * heal ladder and the detached-listener revive both walk straight past it, and the spec string
 * already names it, so the sync task never adds it either. The retry schedule is the only way back,
 * and its shape decides whether a fault that clears by itself is recovered from quietly or a rate
 * that is broken for good fills the log.
 *
 * @since 0.9.72+
 */
public class GraphRetryBackoffDecisionTest {

    /**
     * Holds no state of its own; the retry schedule it checks is a static decision function.
     */
    public GraphRetryBackoffDecisionTest() {}

    ///////////// retryDelayMs

    /**
     * The first retry comes round on the next sync tick, so a fault that clears inside a minute or
     * two - the scratch path held by another user, a filesystem briefly full - never reaches the
     * operator as a lost graph.
     */
    @Test
    public void testFirstRetryWaitsOneSyncPeriod() {
        assertEquals(GraphGenerator.SYNC_INTERVAL_MS, GraphGenerator.retryDelayMs(1));
    }

    /**
     * A zero or negative attempt count is not a thing the caller can legitimately pass, and the
     * delay must still be a sync period rather than zero: a zero delay would re-attempt the rate on
     * every tick of the sync task, which is the log spam the backoff exists to prevent.
     */
    @Test
    public void testNoAttemptCountStillWaitsASyncPeriod() {
        assertEquals(GraphGenerator.SYNC_INTERVAL_MS, GraphGenerator.retryDelayMs(0));
        assertEquals(GraphGenerator.SYNC_INTERVAL_MS, GraphGenerator.retryDelayMs(-5));
    }

    /**
     * The delay grows geometrically, so a rate that stays broken is asked again rarely enough that
     * its error line stays readable.
     */
    @Test
    public void testDelayGrowsGeometrically() {
        long previous = GraphGenerator.retryDelayMs(1);
        for (int attempts = 2; attempts <= 5; attempts++) {
            long next = GraphGenerator.retryDelayMs(attempts);
            assertTrue("attempt " + attempts + " must wait longer than " + previous,
                       next > previous);
            previous = next;
        }
    }

    /**
     * The delay never exceeds the ceiling, whatever the attempt count, and never falls below one
     * sync period, whatever the count. The ceiling is what keeps a permanently broken rate quiet;
     * the floor is what keeps a retry from being scheduled in the past.
     */
    @Test
    public void testDelayStaysWithinItsBounds() {
        for (int attempts = 1; attempts < 64; attempts++) {
            long delay = GraphGenerator.retryDelayMs(attempts);
            assertTrue("attempt " + attempts + " below the floor",
                       delay >= GraphGenerator.SYNC_INTERVAL_MS);
            assertTrue("attempt " + attempts + " above the ceiling",
                       delay <= GraphGenerator.MAX_RETRY_MS);
        }
        // a shift that ran off the end of a long would go negative, so the ceiling has to
        // be reached by arithmetic that cannot overflow
        assertEquals(GraphGenerator.MAX_RETRY_MS, GraphGenerator.retryDelayMs(64));
        assertEquals(GraphGenerator.MAX_RETRY_MS, GraphGenerator.retryDelayMs(Integer.MAX_VALUE));
    }
}
