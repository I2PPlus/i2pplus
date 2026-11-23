package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests the pure pinned-window tracker
 * {@link Connection#noteWindowPinned(long, long, int, int)} and
 * {@link Connection#windowPinnedLongEnough(long, long, long)}.
 *
 * <p>Why this exists at all: {@code stream.con.stallDetected} and
 * {@code stream.con.throttleDetected} both reported "No events" across eight log
 * rotations, while a stream pinned at the loss-cut floor for twenty minutes is
 * the case path rotation was written for. Neither verdict can see it - bytes keep
 * being acknowledged, just slowly, and a stream with a window of a few messages
 * is never below the goodput floor. This measures the pin. Nothing acts on it
 * yet, and that is deliberate: the penalty comes after the counter, not before.
 *
 * @since 0.9.72+
 */
public class ConnectionWindowPinnedTest {

    /** The pin threshold under test, in messages. */
    private static final int CEILING = Connection.PINNED_WINDOW_CEILING_MSGS;

    /** How long a pin must last before it counts, in ms. */
    private static final long REQUIRED = Connection.PINNED_WINDOW_MS;

    /**
     * One instance per test method, as JUnit builds a fresh test object for each
     * one. The helpers under test are static and hold no state.
     *
     * @since 0.9.72+
     */
    public ConnectionWindowPinnedTest() {}

    /** A window above the ceiling clears the tracker, so recovery is visible. */
    @Test
    public void recoveryClearsTheTracker() {
        assertEquals(0, Connection.noteWindowPinned(1_000L, 500L, CEILING + 1, CEILING));
        // and clearing is idempotent - an already-clear tracker stays clear
        assertEquals(0, Connection.noteWindowPinned(2_000L, 0L, 64, CEILING));
    }

    /** The pin is timed from the first sample that saw it, not the last. */
    @Test
    public void pinStartsAtTheFirstSampleThatSeesIt() {
        assertEquals(1_000L, Connection.noteWindowPinned(1_000L, 0L, 4, CEILING));
        long since = Connection.noteWindowPinned(1_000L, 0L, 4, CEILING);
        assertEquals(since, Connection.noteWindowPinned(11_000L, since, 2, CEILING));
        assertEquals(since, Connection.noteWindowPinned(21_000L, since, 8, CEILING));
    }

    /**
     * The boundary: a window exactly at the ceiling is pinned, one message above
     * it is not. The loss cut floors the window at 4, so the ceiling has to sit
     * above that or the pin it names cannot be seen.
     */
    @Test
    public void ceilingBoundaryIsInclusive() {
        assertEquals(7_000L, Connection.noteWindowPinned(7_000L, 0L, CEILING, CEILING));
        assertEquals(0, Connection.noteWindowPinned(7_000L, 1_000L, CEILING + 1, CEILING));
        assertTrue("ceiling " + CEILING + " must sit above the loss-cut floor", CEILING > 4);
    }

    /** A recovered window that pins again starts a new pin, not an old age. */
    @Test
    public void aSecondPinStartsFresh() {
        long first = Connection.noteWindowPinned(1_000L, 0L, 4, CEILING);
        long clear = Connection.noteWindowPinned(61_000L, first, 40, CEILING);
        assertEquals(0, clear);
        assertEquals(71_000L, Connection.noteWindowPinned(71_000L, clear, 4, CEILING));
    }

    /** No pin has no age: a tracker of 0 is not "pinned since the epoch". */
    @Test
    public void noPinHasNoAge() {
        assertFalse(Connection.windowPinnedLongEnough(0, REQUIRED + 10_000L, REQUIRED));
        assertFalse(Connection.windowPinnedLongEnough(-1, REQUIRED + 10_000L, REQUIRED));
    }

    /** The age boundary is inclusive, one sample late rather than one early. */
    @Test
    public void ageBoundaryIsInclusive() {
        long start = 100_000L;
        assertFalse(Connection.windowPinnedLongEnough(start, start + REQUIRED - 1, REQUIRED));
        assertTrue(Connection.windowPinnedLongEnough(start, start + REQUIRED, REQUIRED));
        assertTrue(Connection.windowPinnedLongEnough(start, start + REQUIRED + 60_000L, REQUIRED));
    }

    /**
     * The window has to be reachable at the sample cadence: a pin is only
     * observed when a goodput sample closes, so the required age must be at least
     * a few samples long or the report would fire on the first one.
     */
    @Test
    public void requiredAgeIsLongerThanTheSampleCadence() {
        // samples close every 10s; two minutes is twelve of them
        assertTrue(REQUIRED >= 10 * 1000L);
        assertTrue(REQUIRED <= 10 * 60 * 1000L);
    }
}
