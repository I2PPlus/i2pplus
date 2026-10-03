package net.i2p.client.streaming.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Decision tests for {@link Connection#restoredWindowOnUnchoke}, the window a
 * connection gets back when the remote lifts its choke.
 *
 * <p>The regression: the restore used to be {@code max(saved, ssthresh)} capped
 * at the ceiling. Because {@code ssthresh} starts at {@code maxSlowStartWindow}
 * and is an asymptotic growth target rather than a capacity measurement, that
 * made every unchoke restore the full ceiling instead of the window the
 * connection had actually demonstrated — a connection collapsed to a handful of
 * packets was reinflated to the ceiling on every unchoke. Since a remote only
 * chokes when it is overloaded, the fixed burst landed on a congested path,
 * caused loss, and produced the next choke.
 *
 * @since 0.9.71+
 */
public class ConnectionUnchokeWindowTest {

    /** The ceiling in the reported production configuration. */
    private static final int CEILING = 768;
    /** ssthresh under the old code when maxSlowStartWindow is 2048. */
    private static final int SSTHRESH = 2048;

    // ---- the core fix: restore what was demonstrated, nothing more ----

    /**
     * A window that collapsed well below the ceiling must come back collapsed.
     * Under the old {@code max(saved, ssthresh)} this returned CEILING.
     */
    @Test
    public void testCollapsedWindowIsNotInflatedToCeiling() {
        assertEquals(6, Connection.restoredWindowOnUnchoke(6, CEILING));
        assertEquals(29, Connection.restoredWindowOnUnchoke(29, CEILING));
        assertEquals(1, Connection.restoredWindowOnUnchoke(1, CEILING));
    }

    /**
     * The old behaviour, stated as a test so the regression cannot come back:
     * with ssthresh above the ceiling every unchoke restored the ceiling.
     */
    @Test
    public void testOldBehaviourWouldHaveRestoredCeiling() {
        int old = Math.min(Math.max(6, SSTHRESH), CEILING);
        assertEquals("precondition: ssthresh dominates a small saved window",
                     CEILING, old);
        assertNotEquals("the fix must differ from the old result",
                        old, Connection.restoredWindowOnUnchoke(6, CEILING));
    }

    /** A window below the ceiling round-trips unchanged. */
    @Test
    public void testSavedWindowRestoredExactly() {
        assertEquals(289, Connection.restoredWindowOnUnchoke(289, CEILING));
        assertEquals(CEILING - 1, Connection.restoredWindowOnUnchoke(CEILING - 1, CEILING));
    }

    // ---- the ceiling clamp is preserved ----

    /** A window saved under a more generous ceiling is capped at the current one. */
    @Test
    public void testSavedAboveCeilingIsClamped() {
        assertEquals(CEILING, Connection.restoredWindowOnUnchoke(4096, CEILING));
    }

    @Test
    public void testEqualToCeilingUnchanged() {
        assertEquals(CEILING, Connection.restoredWindowOnUnchoke(CEILING, CEILING));
    }

    // ---- degenerate inputs ----

    /** A zero or negative ceiling must never yield a zero or negative window. */
    @Test
    public void testDegenerateCeilingYieldsOne() {
        assertEquals(1, Connection.restoredWindowOnUnchoke(100, 0));
        assertEquals(1, Connection.restoredWindowOnUnchoke(100, -5));
    }

    /** A zero saved window (no capacity demonstrated) floors at one. */
    @Test
    public void testZeroSavedYieldsOne() {
        assertEquals(1, Connection.restoredWindowOnUnchoke(0, CEILING));
    }

    /** Never above the ceiling, never below one, across the whole range. */
    @Test
    public void testAlwaysWithinBounds() {
        for (int ceiling = 1; ceiling <= 8192; ceiling *= 2) {
            for (int saved = 1; saved <= 8192; saved *= 2) {
                int r = Connection.restoredWindowOnUnchoke(saved, ceiling);
                assertTrue("restore " + r + " out of [1," + ceiling + "] for saved=" +
                           saved + " ceiling=" + ceiling, r >= 1 && r <= ceiling);
            }
        }
    }

    /** Monotonic in the saved window: more demonstrated capacity never restores less. */
    @Test
    public void testMonotonicInSavedWindow() {
        int prev = 0;
        for (int saved = 1; saved <= 4096; saved++) {
            int r = Connection.restoredWindowOnUnchoke(saved, CEILING);
            assertTrue("non-monotonic at saved=" + saved, r >= prev);
            prev = r;
        }
    }
}
