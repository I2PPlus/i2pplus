package net.i2p.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests how the worker count for a timer is resolved.
 *
 * <p>The original figure was two, which is enough only while event volume stays
 * below what two threads can service. A live router measured a 16s median / 30s
 * p90 execution delay across seven different event types, which is volume
 * saturation rather than the two-blocked-events wedge; the pool never grows past
 * its worker count, so that backlog is invisible everywhere except the per-event
 * late-execution warnings.
 *
 * <p>The floor matters more than the default here: dropping below two would mean a
 * single blocked event starves every periodic event on the timer, which is worse
 * than a backlog.
 *
 * @since 0.9.71+
 */
public class SimpleTimer2ThreadsTest {

    /** Unset means the default. */
    @Test
    public void testUnsetUsesDefault() {
        assertEquals(SimpleTimer2.DEFAULT_THREADS, SimpleTimer2.resolveThreads(0));
    }

    /** A configured value above the floor is honoured. */
    @Test
    public void testConfiguredValueHonoured() {
        assertEquals(4, SimpleTimer2.resolveThreads(4));
        assertEquals(6, SimpleTimer2.resolveThreads(6));
        assertEquals(2, SimpleTimer2.resolveThreads(2));
    }

    /**
     * Never below two. One worker means a single blocked event starves everything,
     * which is a strictly worse failure than running behind.
     */
    @Test
    public void testNeverBelowTwo() {
        assertEquals(2, SimpleTimer2.resolveThreads(1));
    }

    /**
     * A non-positive value is malformed input rather than a request for fewer
     * workers, so it falls back to the default like an unset property would.
     */
    @Test
    public void testMalformedValueFallsBackToDefault() {
        assertEquals(SimpleTimer2.DEFAULT_THREADS, SimpleTimer2.resolveThreads(-4));
        assertEquals(SimpleTimer2.DEFAULT_THREADS, SimpleTimer2.resolveThreads(0));
    }

    /** The default itself clears the historical floor it replaced. */
    @Test
    public void testDefaultExceedsTheHistoricalTwo() {
        assertEquals(true, SimpleTimer2.DEFAULT_THREADS > 2);
    }
}
